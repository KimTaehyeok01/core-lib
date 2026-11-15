package com.corelib.example.banword.service;

import com.corelib.banword.BanWordCacheService;
import com.corelib.banword.BanWordUtils;
import com.corelib.example.banword.InMemoryBanWordSource;
import com.corelib.example.banword.dto.BanWordCheckDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** BanWordUtils / BanWordCacheService 를 호출하는 얇은 예제 서비스 */
@Service
@RequiredArgsConstructor
public class BanWordCheckService {

	private final BanWordUtils banWordUtils;
	private final BanWordCacheService cacheService;
	private final InMemoryBanWordSource source;

	/** Aho-Corasick 부분 매칭 (OKT 미사용) */
	public BanWordCheckDto.Response check(BanWordCheckDto.Request request) {
		return new BanWordCheckDto.Response(banWordUtils.getBanWords(request.text()));
	}

	/** 형태소 토큰 경계 정렬 매칭 */
	public BanWordCheckDto.Response checkWithTokens(BanWordCheckDto.Request request) {
		return new BanWordCheckDto.Response(banWordUtils.getBanWordsWithKeywordExtractor(request.text()));
	}

	/** 캐시(Direct 모드에서는 로컬 Trie) 재구성 */
	public void evict() {
		cacheService.evictBanWords();
		cacheService.evictWhiteWords();
	}

	/** InMemory Source 에 금지어를 추가하고 evict 로 Trie 를 재구성한다. */
	public void addBan(BanWordCheckDto.AdminAddRequest request) {
		source.addBan(request.word());
		cacheService.evictBanWords();
	}

	/** InMemory Source 에 화이트 단어를 추가하고 evict 로 Trie 를 재구성한다. */
	public void addWhite(BanWordCheckDto.AdminAddRequest request) {
		source.addWhite(request.word());
		cacheService.evictWhiteWords();
	}
}
