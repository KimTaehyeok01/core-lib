// common 예제를 실제 앱으로 띄워 응답 포맷·전역 예외 핸들러 자동등록 검증
package com.corelib.example.common;

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
class CommonExampleTest {

	private final MockMvc mockMvc;

	/** @return 등록된 상품 id */
	private Number create(String json) throws Exception {
		String body = mockMvc.perform(post("/api/items/create").contentType(MediaType.APPLICATION_JSON).content(json))
							 .andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.data.id");
	}

	@Test
	@DisplayName("등록 → 조회 - ApiResponse 성공 포맷")
	void createAndDetail() throws Exception {
		mockMvc.perform(post("/api/items/create").contentType(MediaType.APPLICATION_JSON)
												 .content("{\"name\":\"키보드\",\"price\":50000,\"stock\":3}"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.success").value(true))
			   .andExpect(jsonPath("$.data.name").value("키보드"));
	}

	@Test
	@DisplayName("없는 상품 - 404 C404 (전역 핸들러 자동등록)")
	void notFound() throws Exception {
		mockMvc.perform(get("/api/items/detail").param("id", "999"))
			   .andExpect(status().isNotFound())
			   .andExpect(jsonPath("$.success").value(false))
			   .andExpect(jsonPath("$.code").value("C404"))
			   .andExpect(jsonPath("$.message").value("상품을 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("검증 실패 - 400 C400, 위반 필드명이 메시지에")
	void validation() throws Exception {
		mockMvc.perform(post("/api/items/create").contentType(MediaType.APPLICATION_JSON)
												 .content("{\"name\":\" \",\"price\":0,\"stock\":1}"))
			   .andExpect(status().isBadRequest())
			   .andExpect(jsonPath("$.code").value("C400"));
	}

	@Test
	@DisplayName("품절 구매 - 409 ITEM_SOLD_OUT (도메인 에러코드)")
	void soldOut() throws Exception {
		Number id = create("{\"name\":\"한정판\",\"price\":1000,\"stock\":0}");

		mockMvc.perform(post("/api/items/purchase").contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":" + id + "}"))
			   .andExpect(status().isConflict())
			   .andExpect(jsonPath("$.code").value("ITEM_SOLD_OUT"));
	}

	@Test
	@DisplayName("예상 못 한 오류 - 500 C500, 내부 메시지 숨김")
	void unexpected() throws Exception {
		mockMvc.perform(get("/api/items/unexpected-error"))
			   .andExpect(status().isInternalServerError())
			   .andExpect(jsonPath("$.code").value("C500"))
			   .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
	}
}
