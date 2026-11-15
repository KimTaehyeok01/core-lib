package com.corelib.banword;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Aho-Corasick Trie 보유자 — 금지어/화이트 데이터 변경 시 stale-while-revalidate 로 재구성한다.
 *
 * <ul>
 *   <li>Snapshot record (Trie + cacheVersion) 단일 volatile 로 원자 교체</li>
 *   <li>cacheVersion 불일치 감지 시 AtomicBoolean 으로 단일 스레드만 재구성, 나머지는 직전 Trie 로 매칭</li>
 *   <li>Source 호출은 모니터 밖 — 느린 I/O 가 전체 요청을 블록하지 않음</li>
 *   <li>DB 원문도 preprocessForDirectMatch 적용 — 좌표계 일치 + normalizedToOriginal 로 원형 반환</li>
 *   <li>SmartInitializingSingleton 으로 기동 시 pre-build — cold path 요청 미발생</li>
 * </ul>
 *
 * <p>화이트리스트도 동일한 Snapshot 패턴을 쓰며 금지어 Trie 와 독립적으로 invalidate 된다.
 */
public class BanWordMatcher implements SmartInitializingSingleton {

	private static final Logger log = LoggerFactory.getLogger(BanWordMatcher.class);

	private final BanWordCacheService cacheService;
	private final BanWordPreprocessor preprocessor;

	private final AtomicLong banCacheVersion = new AtomicLong();
	private final AtomicLong whiteCacheVersion = new AtomicLong();
	private final AtomicBoolean banRebuilding = new AtomicBoolean(false);
	private final AtomicBoolean whiteRebuilding = new AtomicBoolean(false);

	private volatile Snapshot banCurrent;
	private volatile WhiteSnapshot whiteCurrent;
	private volatile Map<String, String> banNormalizedToOriginal = Collections.emptyMap();

	public BanWordMatcher(BanWordCacheService cacheService, BanWordPreprocessor preprocessor) {
		this.cacheService = cacheService;
		this.preprocessor = preprocessor;
	}

	/** 기동 완료 시 Trie 를 미리 빌드한다. 실패해도 warn 로그만 — 최초 요청에서 재시도. */
	@Override
	public void afterSingletonsInstantiated() {
		try {
			rebuildBan();
		} catch (Exception e) {
			log.warn("BanWord Trie pre-build 실패, 최초 요청에서 재시도. cause={}", e.toString());
		}
		try {
			rebuildWhite();
		} catch (Exception e) {
			log.warn("White Trie pre-build 실패, 최초 요청에서 재시도. cause={}", e.toString());
		}
	}

	/** 금지어 데이터가 바뀌었음을 알린다 — 다음 호출에서 재구성 트리거. */
	public void invalidate() {
		banCacheVersion.incrementAndGet();
	}

	/** 화이트리스트 데이터가 바뀌었음을 알린다 — 금지어 Trie 영향 없음. */
	public void invalidateWhite() {
		whiteCacheVersion.incrementAndGet();
	}

	/**
	 * 전처리된 입력에서 금지어 Emit 을 돌려준다. stale-while-revalidate.
	 * current 가 있으면 synchronized 없이 즉시 반환, 버전 불일치는 비동기 재구성.
	 */
	public List<Emit> findBanMatches(String normalized) {
		Snapshot s = banCurrent;
		long seen = banCacheVersion.get();
		if (s != null && s.cacheVersion() == seen) {
			return new ArrayList<>(s.trie().parseText(normalized));
		}
		if (s != null) {
			if (banRebuilding.compareAndSet(false, true)) {
				try {
					rebuildBan();
				} catch (Exception e) {
					log.warn("BanWord Trie 재구성 실패, stale 사용 유지. cause={}", e.toString());
				}
			}
			Snapshot after = banCurrent;
			return new ArrayList<>((after != null ? after : s).trie().parseText(normalized));
		}
		// cold-start 안전망 — 동기 빌드
		synchronized (this) {
			if (banCurrent == null) {
				rebuildBan();
			}
			return new ArrayList<>(banCurrent.trie().parseText(normalized));
		}
	}

	/** 전처리된 입력에서 화이트 Emit 을 돌려준다. */
	public List<Emit> findWhiteMatches(String normalized) {
		WhiteSnapshot s = whiteCurrent;
		long seen = whiteCacheVersion.get();
		if (s != null && s.version() == seen) {
			return new ArrayList<>(s.trie().parseText(normalized));
		}
		if (s != null) {
			if (whiteRebuilding.compareAndSet(false, true)) {
				try {
					rebuildWhite();
				} catch (Exception e) {
					log.warn("White Trie 재구성 실패, stale 사용 유지. cause={}", e.toString());
				}
			}
			WhiteSnapshot after = whiteCurrent;
			return new ArrayList<>((after != null ? after : s).trie().parseText(normalized));
		}
		synchronized (this) {
			if (whiteCurrent == null) {
				rebuildWhite();
			}
			return new ArrayList<>(whiteCurrent.trie().parseText(normalized));
		}
	}

	/** 정규화된 Emit keyword 를 DB 원문으로 되돌린다. 없으면 입력 그대로. */
	public String toOriginal(String normalizedKeyword) {
		String original = banNormalizedToOriginal.get(normalizedKeyword);
		return original != null ? original : normalizedKeyword;
	}

	private void rebuildBan() {
		try {
			long seen = banCacheVersion.get();
			List<String> raw = cacheService.getBanWords();
			Trie.TrieBuilder builder = Trie.builder();
			Map<String, String> n2o = new HashMap<>();
			if (raw != null) {
				for (String word : raw) {
					if (word == null) {
						continue;
					}
					String norm = preprocessor.preprocessForDirectMatch(word);
					if (norm == null || norm.isEmpty()) {
						continue;
					}
					builder.addKeyword(norm);
					n2o.putIfAbsent(norm, word);
				}
			}
			banCurrent = new Snapshot(builder.build(), seen);
			this.banNormalizedToOriginal = n2o;
		} finally {
			banRebuilding.set(false);
		}
	}

	private void rebuildWhite() {
		try {
			long seen = whiteCacheVersion.get();
			List<String> raw = cacheService.getWhiteWords();
			Trie.TrieBuilder builder = Trie.builder();
			if (raw != null) {
				for (String word : raw) {
					if (word == null) {
						continue;
					}
					String norm = preprocessor.preprocessForDirectMatch(word);
					if (norm == null || norm.isEmpty()) {
						continue;
					}
					builder.addKeyword(norm);
				}
			}
			whiteCurrent = new WhiteSnapshot(builder.build(), seen);
		} finally {
			whiteRebuilding.set(false);
		}
	}

	/** 금지어 Trie + 생성 시점의 cacheVersion 을 원자 교체용으로 묶은 스냅샷. */
	private record Snapshot(Trie trie, long cacheVersion) {
	}

	/** 화이트 Trie + 생성 시점의 version 을 묶은 스냅샷. */
	private record WhiteSnapshot(Trie trie, long version) {
	}
}
