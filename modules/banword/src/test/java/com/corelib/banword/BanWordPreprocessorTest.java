package com.corelib.banword;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BanWordPreprocessorTest {

	private final BanWordPreprocessor preprocessor = new BanWordPreprocessor();

	@Test
	@DisplayName("소문자화")
	void lowercase() {
		assertThat(preprocessor.preprocessForDirectMatch("ABCdef")).isEqualTo("abcdef");
	}

	@Test
	@DisplayName("릿스픽 치환 — 0/1/3/4/5/7/@/$ 각각")
	void leetspeak() {
		// 0->o, 1->i, 3->e, 4->a, 5->s, 7->t
		assertThat(preprocessor.preprocessForDirectMatch("h3ll0")).isEqualTo("hello");
		assertThat(preprocessor.preprocessForDirectMatch("5t@r")).isEqualTo("star");
		assertThat(preprocessor.preprocessForDirectMatch("7e$t")).isEqualTo("test");
	}

	@Test
	@DisplayName("공백 제거")
	void removeSpace() {
		assertThat(preprocessor.preprocessForDirectMatch("a b\tc\nd")).isEqualTo("abcd");
	}

	@Test
	@DisplayName("특수문자 필터 — 한글/영문/자음 외 제거 (leetspeak 대상 @/$ 는 영문자로 치환되므로 이 테스트는 비-leetspeak 특수문자로 검증)")
	void filterSpecial() {
		assertThat(preprocessor.preprocessForDirectMatch("한국!?#어")).isEqualTo("한국어");
		assertThat(preprocessor.preprocessForDirectMatch("ㅅ..ㅂ")).isEqualTo("ㅅㅂ");
	}

	@Test
	@DisplayName("릿스픽 후 한글과 결합 — 'ㅆ1발' -> 'ㅆi발'")
	void leetspeakMixedWithHangul() {
		String result = preprocessor.preprocessForDirectMatch("ㅆ1발");

		assertThat(result).isEqualTo("ㅆi발");
	}

	@Test
	@DisplayName("빈 입력은 빈 문자열")
	void blankInput() {
		assertThat(preprocessor.preprocessForDirectMatch(null)).isEmpty();
		assertThat(preprocessor.preprocessForDirectMatch("")).isEmpty();
		assertThat(preprocessor.preprocessForTokenAlign(null)).isEmpty();
		assertThat(preprocessor.preprocessForTokenAlign("")).isEmpty();
	}

	@Test
	@DisplayName("preprocessForTokenAlign 는 OKT normalize 까지 적용")
	void tokenAlignIncludesNormalize() {
		String result = preprocessor.preprocessForTokenAlign("씨발ㅋㅋㅋㅋㅋ");

		assertThat(result).isNotEmpty();
		assertThat(result).startsWith("씨발");
	}
}
