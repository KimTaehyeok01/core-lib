package com.corelib.example.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

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

	private ResultActions requestDelete(String shareCode, String pin) throws Exception {
		return mockMvc.perform(post("/api/posts/delete").contentType(MediaType.APPLICATION_JSON)
														.content("{\"shareCode\":\"" + shareCode + "\",\"deletePin\":\"" + pin + "\"}"));
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

	@Test
	@DisplayName("삭제 PIN 오답 10건 동시 - 5건만 PIN 비교(C403), 나머지는 잠금(423), 잠긴 뒤엔 맞는 PIN 도 거부")
	void deleteLockedAfterFailures() throws Exception {
		String body = create("{\"title\":\"잠길 글\",\"content\":\"내용\"}");
		String shareCode = JsonPath.read(body, "$.data.shareCode");
		String pin = JsonPath.read(body, "$.data.deletePin");
		String wrongPin = pin.equals("000000") ? "111111" : "000000";

		int count = 10;
		ExecutorService executor = Executors.newFixedThreadPool(count);
		CountDownLatch ready = new CountDownLatch(count);
		CountDownLatch start = new CountDownLatch(1);
		List<String> codes = new ArrayList<>();
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return JsonPath.<String>read(requestDelete(shareCode, wrongPin).andReturn().getResponse().getContentAsString(), "$.code");
				}));
			}
			ready.await(5, TimeUnit.SECONDS);
			start.countDown();
			for (Future<String> future : futures) {
				codes.add(future.get(10, TimeUnit.SECONDS));
			}
		} finally {
			executor.shutdownNow();
		}

		assertThat(codes).filteredOn("C403"::equals).hasSize(5);
		assertThat(codes).filteredOn("POST_DELETE_LOCKED"::equals).hasSize(5);
		requestDelete(shareCode, pin)
			.andExpect(status().is(423))
			.andExpect(jsonPath("$.code").value("POST_DELETE_LOCKED"));
		mockMvc.perform(get("/api/posts/detail").param("shareCode", shareCode))
			   .andExpect(status().isOk());
	}
}
