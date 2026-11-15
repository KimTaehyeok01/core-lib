package com.corelib.banword;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.ahocorasick.trie.Emit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BanWordMatcherTest {

	private final BanWordPreprocessor preprocessor = new BanWordPreprocessor();

	@Test
	@DisplayName("빈 금지어 Source 는 매칭 결과 비어 있음")
	void emptyTableReturnsNoMatches() {
		StubCacheService cache = new StubCacheService(List.of(), List.of());
		BanWordMatcher matcher = buildPreBuilt(cache);

		assertThat(matcher.findBanMatches("아무문자열")).isEmpty();
	}

	@Test
	@DisplayName("부분 매칭 — 긴 단어 안의 짧은 금지어도 Emit 으로 올라온다")
	void partialMatch() {
		StubCacheService cache = new StubCacheService(List.of("게이"), List.of());
		BanWordMatcher matcher = buildPreBuilt(cache);

		assertThat(extractKeywords(matcher.findBanMatches("네비게이션"))).contains("게이");
	}

	@Test
	@DisplayName("DB 원문 FUCK/ㅆ1발/시 발 가 소문자/릿스픽/공백제거 입력과 매칭되고 원형 반환")
	void preprocessAppliedToSource() {
		StubCacheService cache = new StubCacheService(List.of("FUCK", "ㅆ1발", "시 발"), List.of());
		BanWordMatcher matcher = buildPreBuilt(cache);

		// FUCK → 소문자 fuck 으로 저장되어야 하는데 현 preprocess 는 대문자/소문자 정규화. "fuck" 입력 매칭.
		List<Emit> fuck = matcher.findBanMatches(preprocessor.preprocessForDirectMatch("fuck"));
		assertThat(fuck).isNotEmpty();
		assertThat(matcher.toOriginal(fuck.get(0).getKeyword())).isEqualTo("FUCK");

		// ㅆ1발 → ㅆi발 로 저장. 입력 "ㅆ1발" 도 동일 정규화되어 매칭.
		List<Emit> ssi = matcher.findBanMatches(preprocessor.preprocessForDirectMatch("ㅆ1발"));
		assertThat(ssi).isNotEmpty();
		assertThat(matcher.toOriginal(ssi.get(0).getKeyword())).isEqualTo("ㅆ1발");

		// "시 발" → 공백 제거 "시발". 입력 "시발" 매칭.
		List<Emit> sibal = matcher.findBanMatches(preprocessor.preprocessForDirectMatch("시발"));
		assertThat(sibal).isNotEmpty();
		assertThat(matcher.toOriginal(sibal.get(0).getKeyword())).isEqualTo("시 발");
	}

	@Test
	@DisplayName("invalidate 후 다음 호출 시 Trie 재구성 — 새 금지어 반영")
	void invalidateRebuilds() {
		StubCacheService cache = new StubCacheService(List.of("나쁜말"), List.of());
		BanWordMatcher matcher = buildPreBuilt(cache);

		// 1차 매칭
		assertThat(extractKeywords(matcher.findBanMatches("나쁜말테스트"))).containsExactly("나쁜말");

		// 금지어 추가 후 invalidate
		cache.setBanWords(List.of("나쁜말", "추가어"));
		matcher.invalidate();

		// stale-while-revalidate: 첫 호출에서 재구성 트리거 → 같은 스레드가 즉시 완료하므로 바로 반영
		assertThat(extractKeywords(matcher.findBanMatches("추가어내용"))).containsExactly("추가어");
	}

	@Test
	@DisplayName("기동 pre-build 후 첫 호출이 cold path 를 경유하지 않는다")
	void preBuildAvoidsCold() {
		StubCacheService cache = new StubCacheService(List.of("욕설"), List.of());
		BanWordMatcher matcher = new BanWordMatcher(cache, preprocessor);
		matcher.afterSingletonsInstantiated();

		// Source 를 null 로 바꿔도 Trie 가 이미 있으므로 즉시 매칭 가능 (cold 재진입 없음)
		cache.setBanWords(null);
		assertThat(extractKeywords(matcher.findBanMatches("욕설네"))).containsExactly("욕설");
	}

	@Test
	@DisplayName("동시 findBanMatches 호출 중 invalidate — NPE/블록 없이 정상 동작, 재구성 중 이전 Trie 사용")
	void concurrentFindAndInvalidate() throws InterruptedException {
		StubCacheService cache = new StubCacheService(List.of("나쁜"), List.of());
		BanWordMatcher matcher = buildPreBuilt(cache);

		int threads = 10;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch latch = new CountDownLatch(threads);
		List<Throwable> errors = new CopyOnWriteArrayList<>();

		for (int i = 0; i < threads; i++) {
			executor.submit(() -> {
				try {
					for (int j = 0; j < 50; j++) {
						matcher.findBanMatches("나쁜말을했다");
						if (j % 10 == 0) {
							matcher.invalidate();
						}
					}
				} catch (Throwable t) {
					errors.add(t);
				} finally {
					latch.countDown();
				}
			});
		}
		latch.await(10, TimeUnit.SECONDS);
		executor.shutdown();

		assertThat(errors).isEmpty();
	}

	@Test
	@DisplayName("화이트 invalidate 는 금지어 Trie 영향 없음")
	void whiteInvalidateDoesNotAffectBan() {
		StubCacheService cache = new StubCacheService(List.of("나쁜"), List.of("네비게이션"));
		BanWordMatcher matcher = buildPreBuilt(cache);

		matcher.invalidateWhite();

		assertThat(extractKeywords(matcher.findBanMatches("나쁜말"))).containsExactly("나쁜");
	}

	private BanWordMatcher buildPreBuilt(BanWordCacheService cache) {
		BanWordMatcher matcher = new BanWordMatcher(cache, preprocessor);
		matcher.afterSingletonsInstantiated();
		return matcher;
	}

	private static List<String> extractKeywords(java.util.Collection<Emit> emits) {
		List<String> result = new ArrayList<>();
		for (Emit emit : emits) {
			result.add(emit.getKeyword());
		}
		return result;
	}

	private static class StubCacheService implements BanWordCacheService {

		private volatile List<String> banWords;
		private volatile List<String> whiteWords;

		StubCacheService(List<String> banWords, List<String> whiteWords) {
			this.banWords = banWords;
			this.whiteWords = whiteWords;
		}

		void setBanWords(List<String> banWords) {
			this.banWords = banWords;
		}

		@Override
		public List<String> getBanWords() {
			return banWords;
		}

		@Override
		public List<String> getWhiteWords() {
			return whiteWords;
		}

		@Override
		public void evictBanWords() {
		}

		@Override
		public void evictWhiteWords() {
		}
	}
}
