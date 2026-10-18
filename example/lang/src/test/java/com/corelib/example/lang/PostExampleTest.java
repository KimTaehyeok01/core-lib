// 게시판 예제를 실제 앱으로 띄워 등록 → 목록·상세 → 삭제 흐름과 입력값 정리·마스킹 검증
package com.corelib.example.lang;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class PostExampleTest {

	private final MockMvc mockMvc;

	private String create(String json) throws Exception {
		return mockMvc.perform(post("/api/posts/create").contentType(MediaType.APPLICATION_JSON).content(json))
					  .andExpect(status().isOk())
					  .andExpect(jsonPath("$.data.shareCode").value(matchesPattern("[A-Za-z0-9]{8}")))
					  .andExpect(jsonPath("$.data.deletePin").value(matchesPattern("[0-9]{6}")))
					  .andReturn().getResponse().getContentAsString();
	}

	@Test
	@DisplayName("등록 - 제목 trim, 태그 정리(공백·중복·# 제거, 소문자), 작성자·이메일 마스킹, 요약 길이 제한")
	void createAndRead() throws Exception {
		String body = create("{\"title\":\"  첫 글  \",\"content\":\"" + "가".repeat(80) + "\",\"authorName\":\" 홍길동 \","
			+ "\"authorEmail\":\"hong@example.com\",\"tags\":[\" #Java\",\"\",\"java\",\"Spring\",null]}");
		String shareCode = JsonPath.read(body, "$.data.shareCode");

		mockMvc.perform(get("/api/posts/detail").param("shareCode", shareCode))
			   .andExpect(jsonPath("$.data.title").value("첫 글"))
			   .andExpect(jsonPath("$.data.authorName").value("홍*동"))
			   .andExpect(jsonPath("$.data.maskedAuthorEmail").value("ho**@example.com"))
			   .andExpect(jsonPath("$.data.tags.length()").value(2))
			   .andExpect(jsonPath("$.data.tags[0]").value("java"))
			   .andExpect(jsonPath("$.data.tags[1]").value("spring"));

		mockMvc.perform(get("/api/posts/list").param("tag", "#JAVA"))
			   .andExpect(jsonPath("$.data[0].shareCode").value(shareCode))
			   .andExpect(jsonPath("$.data[0].summary").value("가".repeat(50) + "…"));
	}

	@Test
	@DisplayName("작성자 비우면 익명, 제목 공백만이면 C400")
	void anonymousAndValidation() throws Exception {
		String shareCode = JsonPath.read(create("{\"title\":\"질문\",\"content\":\"내용\",\"authorName\":\"  \"}"), "$.data.shareCode");
		mockMvc.perform(get("/api/posts/detail").param("shareCode", shareCode))
			   .andExpect(jsonPath("$.data.authorName").value("익명"))
			   .andExpect(jsonPath("$.data.tags.length()").value(0));

		mockMvc.perform(post("/api/posts/create").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"   \",\"content\":\"내용\"}"))
			   .andExpect(status().isBadRequest())
			   .andExpect(jsonPath("$.code").value("C400"));
	}

	@Test
	@DisplayName("삭제 - PIN 이 틀리면 C403, 맞으면 삭제 후 C404")
	void delete() throws Exception {
		String body = create("{\"title\":\"지울 글\",\"content\":\"내용\"}");
		String shareCode = JsonPath.read(body, "$.data.shareCode");
		String pin = JsonPath.read(body, "$.data.deletePin");
		String wrongPin = pin.equals("000000") ? "111111" : "000000";

		mockMvc.perform(post("/api/posts/delete").contentType(MediaType.APPLICATION_JSON)
												 .content("{\"shareCode\":\"" + shareCode + "\",\"deletePin\":\"" + wrongPin + "\"}"))
			   .andExpect(status().isForbidden());
		mockMvc.perform(post("/api/posts/delete").contentType(MediaType.APPLICATION_JSON)
												 .content("{\"shareCode\":\"" + shareCode + "\",\"deletePin\":\"" + pin + "\"}"))
			   .andExpect(jsonPath("$.success").value(true));
		mockMvc.perform(get("/api/posts/detail").param("shareCode", shareCode))
			   .andExpect(status().isNotFound());
	}
}
