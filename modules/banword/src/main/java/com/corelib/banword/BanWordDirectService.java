package com.corelib.banword;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 캐시 없이 Source 를 매 호출마다 조회하는 폴백 구현.
 *
 * <p>Redis 가 classpath 에 없거나 {@code redisCacheManager} 빈이 없을 때 자동 선택된다.
 * evict 자체는 저장할 캐시가 없어 {@link BanWordMatcher} 재구성 요청만 트리거한다.
 */
public class BanWordDirectService implements BanWordCacheService {

	private final BanWordSource source;
	private BanWordMatcher matcher;

	public BanWordDirectService(BanWordSource source) {
		this.source = source;
	}

	/** {@link BanWordInitialize} 가 Matcher 생성 후 setter 주입한다 — 순환 참조 회피. */
	void bindMatcher(BanWordMatcher matcher) {
		this.matcher = matcher;
	}

	@Override
	public List<String> getBanWords() {
		List<String> raw = source.getBanWords();
		return raw == null ? Collections.emptyList() : new ArrayList<>(raw);
	}

	@Override
	public List<String> getWhiteWords() {
		List<String> raw = source.getWhiteWords();
		return raw == null ? Collections.emptyList() : new ArrayList<>(raw);
	}

	@Override
	public void evictBanWords() {
		if (matcher != null) {
			matcher.invalidate();
		}
	}

	@Override
	public void evictWhiteWords() {
		if (matcher != null) {
			matcher.invalidateWhite();
		}
	}
}
