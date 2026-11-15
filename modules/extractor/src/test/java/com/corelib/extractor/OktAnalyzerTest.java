package com.corelib.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OktAnalyzerTest {

	@Test
	@DisplayName("tokenize — 토큰 텍스트/품사/offset/length 가 모두 채워진다")
	void tokenizePopulatesFields() {
		List<KeywordExtractorDto.Token> tokens = OktAnalyzer.tokenize("한국어 분석기");

		assertThat(tokens).isNotEmpty();
		for (KeywordExtractorDto.Token t : tokens) {
			assertThat(t.getText()).isNotBlank();
			assertThat(t.getPos()).isNotBlank();
			assertThat(t.getOffset()).isGreaterThanOrEqualTo(0);
			assertThat(t.getLength()).isGreaterThan(0);
		}
	}

	@Test
	@DisplayName("normalize — '씨발ㅋㅋㅋㅋㅋ' 는 앞쪽 '씨발' 을 유지한다")
	void normalizeKeepsLeadingText() {
		String normalized = OktAnalyzer.normalize("씨발ㅋㅋㅋㅋㅋ");

		assertThat(normalized).startsWith("씨발");
	}
}
