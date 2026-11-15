package com.corelib.banword;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Redis 기반 금지어/화이트 캐시 — {@link CacheManager} 의 {@link Cache} API 를 직접 호출한다.
 *
 * <p>라이브러리가 {@code @EnableCaching} 을 활성화하지 않으므로 {@code @Cacheable} / {@code @CacheEvict} AOP 는
 * 동작하지 않는다. 대신 {@code cache.get(key, Callable)} 로 미스시 로드, {@code cache.evict(key)} 로 명시 제거한다.
 *
 * <p>evict 흐름은 Redis DEL → {@link BanWordMatcher#invalidate()} 순서로 호출해,
 * 재구성이 이미 삭제된 캐시를 기준으로 하도록 한다 (영구 stale 방지).
 *
 * <p><b>다중 인스턴스 주의</b>: Redis 캐시는 인스턴스 간 공유되지만, 각 JVM 의 로컬 Trie 는 공유되지 않는다.
 * 데이터 변경 후 다른 인스턴스의 {@code evictBanWords} / {@code evictWhiteWords} 를 호출해야 전체 반영된다.
 * 소비자가 Pub/Sub 등으로 브로드캐스트하는 것이 권장된다.
 */
public class BanWordRedisService implements BanWordCacheService {

	static final String BAN_CACHE = "banWords";
	static final String WHITE_CACHE = "whiteWords";
	static final String ALL_KEY = "all";

	private final BanWordSource source;
	private final CacheManager cacheManager;
	private BanWordMatcher matcher;

	public BanWordRedisService(BanWordSource source, CacheManager cacheManager) {
		this.source = source;
		this.cacheManager = cacheManager;
	}

	/** {@link BanWordInitialize} 가 Matcher 생성 후 setter 주입한다 — 순환 참조 회피. */
	void bindMatcher(BanWordMatcher matcher) {
		this.matcher = matcher;
	}

	@Override
	public List<String> getBanWords() {
		Cache cache = cacheManager.getCache(BAN_CACHE);
		if (cache == null) {
			return copy(source.getBanWords());
		}
		List<String> cached = loadThroughCache(cache, () -> copy(source.getBanWords()), "금지어 캐시 조회 실패");
		return cached != null ? cached : Collections.emptyList();
	}

	@Override
	public List<String> getWhiteWords() {
		Cache cache = cacheManager.getCache(WHITE_CACHE);
		if (cache == null) {
			return copy(source.getWhiteWords());
		}
		List<String> cached = loadThroughCache(cache, () -> copy(source.getWhiteWords()), "화이트리스트 캐시 조회 실패");
		return cached != null ? cached : Collections.emptyList();
	}

	/**
	 * {@link Cache#get(Object, Callable)} 는 Callable 예외를 {@link Cache.ValueRetrievalException}
	 * 으로 래핑한다. Source 가 던진 {@link CoreException} 이 글로벌 핸들러에서 일반 500 으로 떨어지지 않도록 언래핑한다.
	 */
	private static List<String> loadThroughCache(Cache cache, Callable<List<String>> loader, String errorMessage) {
		try {
			return cache.get(ALL_KEY, loader);
		} catch (Cache.ValueRetrievalException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException re) {
				throw re;
			}
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, errorMessage, cause);
		}
	}

	@Override
	public void evictBanWords() {
		Cache cache = cacheManager.getCache(BAN_CACHE);
		if (cache != null) {
			cache.evict(ALL_KEY);
		}
		if (matcher != null) {
			matcher.invalidate();
		}
	}

	@Override
	public void evictWhiteWords() {
		Cache cache = cacheManager.getCache(WHITE_CACHE);
		if (cache != null) {
			cache.evict(ALL_KEY);
		}
		if (matcher != null) {
			matcher.invalidateWhite();
		}
	}

	private static List<String> copy(List<String> src) {
		return src == null ? Collections.emptyList() : new ArrayList<>(src);
	}
}
