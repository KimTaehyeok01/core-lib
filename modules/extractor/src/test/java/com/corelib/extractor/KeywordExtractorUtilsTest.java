package com.corelib.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KeywordExtractorUtilsTest {

	@Test
	@DisplayName("extractKeywords BASIC 품사(Noun 만) 필터")
	void extractKeywordsBasic() {
		KeywordExtractorDto.Request request = KeywordExtractorDto.Request.builder()
			.text("나는 사과를 먹었다")
			.mode(KeywordExtractorDto.Mode.OKT)
			.pos(KeywordExtractorDto.Pos.BASIC)
			.build();

		Set<String> result = KeywordExtractorUtils.extractKeywords(request);

		assertThat(result).contains("사과");
	}

	@Test
	@DisplayName("extractKeywords ALL 품사 — 동사/형용사까지 포함")
	void extractKeywordsAll() {
		KeywordExtractorDto.Request request = KeywordExtractorDto.Request.builder()
			.text("아주 맛있는 사과")
			.pos(KeywordExtractorDto.Pos.ALL)
			.build();

		Set<String> result = KeywordExtractorUtils.extractKeywords(request);

		assertThat(result).contains("사과");
		assertThat(result.size()).isGreaterThanOrEqualTo(2);
	}

	@Test
	@DisplayName("extractTokens offset/length — '미친년' -> [미친 Adj 0 2][년 Noun 2 1]")
	void extractTokensMichinyeon() {
		List<KeywordExtractorDto.Token> tokens = KeywordExtractorUtils.extractTokens("미친년");

		assertThat(tokens).hasSizeGreaterThanOrEqualTo(2);
		KeywordExtractorDto.Token first = tokens.get(0);
		assertThat(first.getText()).isEqualTo("미친");
		assertThat(first.getOffset()).isEqualTo(0);
		assertThat(first.getLength()).isEqualTo(2);

		KeywordExtractorDto.Token second = tokens.get(1);
		assertThat(second.getText()).isEqualTo("년");
		assertThat(second.getOffset()).isEqualTo(2);
		assertThat(second.getLength()).isEqualTo(1);
	}

	@Test
	@DisplayName("normalize 길이 변동 — '씨발ㅋㅋㅋㅋㅋ' 축약")
	void normalizeShortening() {
		String normalized = KeywordExtractorUtils.normalize("씨발ㅋㅋㅋㅋㅋ");

		assertThat(normalized).isNotNull();
		// OKT 는 'ㅋ' 반복을 3개로 축약하는 경향 — 원문보다 짧아야 한다
		assertThat(normalized.length()).isLessThan("씨발ㅋㅋㅋㅋㅋ".length());
		assertThat(normalized).startsWith("씨발");
	}

	@Test
	@DisplayName("null/blank 입력은 빈 결과")
	void blankInput() {
		assertThat(KeywordExtractorUtils.extractTokens(null)).isEmpty();
		assertThat(KeywordExtractorUtils.extractTokens("")).isEmpty();
		assertThat(KeywordExtractorUtils.extractTokens("   ")).isEmpty();
		assertThat(KeywordExtractorUtils.normalize(null)).isEmpty();
		assertThat(KeywordExtractorUtils.normalize("")).isEmpty();

		KeywordExtractorDto.Request blankRequest = KeywordExtractorDto.Request.builder().text("   ").build();
		assertThat(KeywordExtractorUtils.extractKeywords(blankRequest)).isEmpty();
	}
}
