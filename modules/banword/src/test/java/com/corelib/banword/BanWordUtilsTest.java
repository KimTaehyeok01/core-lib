package com.corelib.banword;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.exception.CoreException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

class BanWordUtilsTest {

	private StubCacheService cache;
	private BanWordMatcher matcher;
	private BanWordUtils utils;

	@BeforeEach
	void setUp() {
		cache = new StubCacheService();
		BanWordPreprocessor preprocessor = new BanWordPreprocessor();
		matcher = new BanWordMatcher(cache, preprocessor);
		BanWordProperty property = new BanWordProperty();
		utils = new BanWordUtils(preprocessor, matcher, property);
	}

	@Test
	@DisplayName("getBanWords 부분 매칭 — '네비게이션' 안의 '게이' 가 잡힌다 (화이트 없음)")
	void partialMatch() {
		cache.ban(List.of("게이"));
		matcher.afterSingletonsInstantiated();

		assertThat(utils.getBanWords("네비게이션")).containsExactly("게이");
	}

	@Test
	@DisplayName("getBanWords 화이트 Emit 포함 — '네비게이션' 이 화이트면 그 안의 '게이' 가 제외된다")
	void whitelistEmitCoverage() {
		cache.ban(List.of("게이"));
		cache.white(List.of("네비게이션"));
		matcher.afterSingletonsInstantiated();

		assertThat(utils.getBanWords("네비게이션")).isEmpty();
	}

	@Test
	@DisplayName("getBanWords — 화이트 Emit 구간 밖 금지어는 그대로 채택")
	void whiteOutsideEmit() {
		cache.ban(List.of("게이"));
		cache.white(List.of("네비게이션"));
		matcher.afterSingletonsInstantiated();

		// "네비게이션 게이" — 뒤의 "게이" 는 화이트 구간 밖
		assertThat(utils.getBanWords("네비게이션 게이")).containsExactly("게이");
	}

	@Test
	@DisplayName("getBanWords 빈/blank 입력")
	void blankInput() {
		cache.ban(List.of("게이"));
		matcher.afterSingletonsInstantiated();

		assertThat(utils.getBanWords(null)).isEmpty();
		assertThat(utils.getBanWords("")).isEmpty();
		assertThat(utils.getBanWords("   ")).isEmpty();
	}

	@Test
	@DisplayName("빈 금지어 Source")
	void emptyBanWords() {
		cache.ban(List.of());
		matcher.afterSingletonsInstantiated();

		assertThat(utils.getBanWords("씨발 나쁜놈")).isEmpty();
	}

	@Test
	@DisplayName("입력 길이 초과 → TEXT_TOO_LONG")
	void textTooLong() {
		cache.ban(List.of("x"));
		matcher.afterSingletonsInstantiated();
		BanWordProperty small = new BanWordProperty();
		small.setMaxTextLength(5);
		BanWordUtils tight = new BanWordUtils(new BanWordPreprocessor(), matcher, small);

		assertThatThrownBy(() -> tight.getBanWords("1234567890"))
			.isInstanceOf(CoreException.class)
			.satisfies(ex -> assertThat(((CoreException) ex).getErrorCode())
				.isEqualTo(BanWordError.TEXT_TOO_LONG));
	}

	@Test
	@DisplayName("DB 원문 반환 — 정규화된 매칭 결과를 원형으로 돌려준다")
	void returnsOriginalForm() {
		cache.ban(List.of("FUCK"));
		matcher.afterSingletonsInstantiated();

		assertThat(utils.getBanWords("오 fuck 짜증")).containsExactly("FUCK");
	}

	@Test
	@DisplayName("getBanWordsWithKeywordExtractor — 토큰 경계 정렬된 '미친' 은 채택")
	void tokenAlignedFullWord() {
		cache.ban(List.of("미친"));
		matcher.afterSingletonsInstantiated();

		assertThat(utils.getBanWordsWithKeywordExtractor("나는 미친 사람이다")).contains("미친");
	}

	@Test
	@DisplayName("getBanWordsWithKeywordExtractor — 자음만 금지어 'ㅅㅂ' 는 토큰 경계 무시하고 채택")
	void consonantOnlyBypass() {
		cache.ban(List.of("ㅅㅂ"));
		matcher.afterSingletonsInstantiated();

		assertThat(utils.getBanWordsWithKeywordExtractor("정말 ㅅㅂ 이게뭐야")).containsExactly("ㅅㅂ");
	}

	@Test
	@DisplayName("getBanWordsWithKeywordExtractor — 영문 금지어도 토큰 경계 무시하고 채택")
	void alphaOnlyBypass() {
		cache.ban(List.of("fuck"));
		matcher.afterSingletonsInstantiated();

		assertThat(utils.getBanWordsWithKeywordExtractor("오 fuck 짜증나")).containsExactly("fuck");
	}

	@Test
	@DisplayName("Matcher Emit 좌표계 일치 가드 — 매칭과 토큰 분석이 같은 normalized 를 공유 (OKT normalize 로 ㅋㅋㅋㅋㅋ→ㅋㅋㅋ 길이 변동 상황)")
	void coordinateSystemGuard() {
		cache.ban(List.of("씨발"));
		matcher.afterSingletonsInstantiated();

		// 입력 '씨발ㅋㅋㅋㅋㅋ' → preprocessForTokenAlign 후 '씨발ㅋㅋㅋ'.
		// matcher.findBanMatches 와 extractTokens 가 같은 normalized 문자열을 쓰지 않으면 좌표 어긋남 → [] 반환.
		// 공유를 지키면 '씨발' Emit 의 start/end 가 토큰 '씨발' 경계와 일치해 채택된다.
		assertThat(utils.getBanWordsWithKeywordExtractor("씨발ㅋㅋㅋㅋㅋ")).contains("씨발");
	}

	@Test
	@Disabled("FilteredClassLoader 는 JVM 에 이미 로드된 KeywordExtractorUtils 를 가리지 못함 — "
		+ "격리 테스트 모듈이 있어야 재현 가능. BanWordUtils.getBanWordsWithKeywordExtractor 의 "
		+ "NoClassDefFoundError catch → CoreException(EXTRACTOR_NOT_AVAILABLE) 변환은 코드 레벨 검증.")
	@DisplayName("extractor 패키지가 classpath 에 없으면 getBanWordsWithKeywordExtractor 는 EXTRACTOR_NOT_AVAILABLE")
	void extractorNotAvailable() {
		// FilteredClassLoader 가 com.corelib.extractor 패키지의 모든 클래스 로드를 거부.
		// BanWordUtils 내부가 KeywordExtractorUtils 를 호출하는 순간 NoClassDefFoundError 발생 → CoreException 으로 변환되는지 검증.
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(BanWordInitialize.class))
			.withUserConfiguration(StubSourceConfig.class)
			.withClassLoader(new FilteredClassLoader("com.corelib.extractor"))
			.run(context -> {
				assertThat(context).hasNotFailed();
				BanWordUtils utils = context.getBean(BanWordUtils.class);
				assertThatThrownBy(() -> utils.getBanWordsWithKeywordExtractor("아무 입력이든 상관없다"))
					.isInstanceOf(CoreException.class)
					.satisfies(ex -> assertThat(((CoreException) ex).getErrorCode())
						.isEqualTo(BanWordError.EXTRACTOR_NOT_AVAILABLE));
			});
	}

	@TestConfiguration
	static class StubSourceConfig {

		@Bean
		BanWordSource stubSource() {
			return new BanWordSource() {
				@Override
				public List<String> getBanWords() {
					return Collections.emptyList();
				}

				@Override
				public List<String> getWhiteWords() {
					return Collections.emptyList();
				}
			};
		}
	}

	private static class StubCacheService implements BanWordCacheService {

		private volatile List<String> banWords = List.of();
		private volatile List<String> whiteWords = List.of();

		void ban(List<String> words) {
			this.banWords = words;
		}

		void white(List<String> words) {
			this.whiteWords = words;
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
