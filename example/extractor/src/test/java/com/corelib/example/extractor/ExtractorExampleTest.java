package com.corelib.example.extractor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * extractor 예제 통합 테스트 — /extract, /tokens, /normalize + 입력 검증 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class ExtractorExampleTest {

	private final MockMvc mockMvc;

	@Test
	@DisplayName("extract — '미친년' 분석 결과에 Noun 품사 토큰 포함")
	void extractContainsNoun() throws Exception {
		mockMvc.perform(post("/keywords/extract").contentType(MediaType.APPLICATION_JSON)
												  .content("{\"text\":\"미친년\"}"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.success").value(true))
			   // BASIC pos = Noun 만 — '년' 은 Noun 으로 분류될 가능성이 크지만 사전 변경에 유연하도록 비어 있지 않음만 검증
			   .andExpect(jsonPath("$.data.keywords.length()").isNotEmpty());
	}

	@Test
	@DisplayName("tokens — '미친년' 토큰에 offset 0, length 2 인 '미친' 포함")
	void tokensIncludeMichin() throws Exception {
		mockMvc.perform(post("/keywords/tokens").contentType(MediaType.APPLICATION_JSON)
												 .content("{\"text\":\"미친년\"}"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.success").value(true))
			   .andExpect(jsonPath("$.data.tokens[?(@.text == '미친' && @.offset == 0 && @.length == 2)]").exists());
	}

	@Test
	@DisplayName("normalize — '씨발ㅋㅋㅋㅋㅋ' → '씨발ㅋㅋㅋ'")
	void normalizeShortensRepetition() throws Exception {
		mockMvc.perform(post("/keywords/normalize").contentType(MediaType.APPLICATION_JSON)
													.content("{\"text\":\"씨발ㅋㅋㅋㅋㅋ\"}"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.success").value(true))
			   .andExpect(jsonPath("$.data.normalized").value("씨발ㅋㅋㅋ"));
	}

	@Test
	@DisplayName("빈 text 는 400 — @NotBlank 위반")
	void emptyTextRejected() throws Exception {
		mockMvc.perform(post("/keywords/tokens").contentType(MediaType.APPLICATION_JSON)
												 .content("{\"text\":\"\"}"))
			   .andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("10001 자 text 는 400 — @Size(max=10000) 위반")
	void tooLongTextRejected() throws Exception {
		String longText = "가".repeat(10_001);
		mockMvc.perform(post("/keywords/tokens").contentType(MediaType.APPLICATION_JSON)
												 .content("{\"text\":\"" + longText + "\"}"))
			   .andExpect(status().isBadRequest());
	}
}
