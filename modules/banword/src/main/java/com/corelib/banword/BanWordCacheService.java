package com.corelib.banword;

import java.util.List;

/**
 * 금지어 캐시 서비스 — Redis 구현({@link BanWordRedisService})과 직접 조회 폴백({@link BanWordDirectService}) 이 있다.
 * evict 는 양쪽 모두 {@link BanWordMatcher#invalidate()} 를 함께 호출해 Trie 재구성 시점을 일원화한다.
 */
public interface BanWordCacheService {

	/** 금지어 전체 목록 — 캐시 있으면 캐시에서, 없으면 DB. */
	List<String> getBanWords();

	/** 화이트리스트 전체 목록 — 캐시 있으면 캐시에서, 없으면 DB. */
	List<String> getWhiteWords();

	/** 금지어 캐시 비우고 Trie 재구성 요청. */
	void evictBanWords();

	/** 화이트리스트 캐시 비우고 Trie 재구성 요청. */
	void evictWhiteWords();
}
