package com.corelib.example.banword;

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
 * banword 예제 통합 테스트 — Controller → Service → BanWordUtils 전체 경로.
 * InMemoryBanWordSource 가 매 테스트마다 공유되므로 /admin/ban 으로 추가된 단어는 테스트 격리를 위해 의도적으로 고유 단어를 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class BanWordExampleTest {

	private final MockMvc mockMvc;

	@Test
	@DisplayName("check — 금지어 '씨발' 입력 매칭, 원형 반환")
	void checkMatchesBanWord() throws Exception {
		mockMvc.perform(post("/banwords/check").contentType(MediaType.APPLICATION_JSON)
												.content("{\"text\":\"씨발 짜증\"}"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.success").value(true))
			   .andExpect(jsonPath("$.data.banWords[0]").value("씨발"));
	}

	@Test
	@DisplayName("check — 화이트리스트 '네비게이션' 안의 '게이' 는 Emit 포함 비교로 제외 (v1 결함 회귀 방어)")
	void whitelistEmitCoverage() throws Exception {
		mockMvc.perform(post("/banwords/check").contentType(MediaType.APPLICATION_JSON)
												.content("{\"text\":\"네비게이션 켜줘\"}"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.success").value(true))
			   .andExpect(jsonPath("$.data.banWords.length()").value(0));
	}

	@Test
	@DisplayName("check-with-tokens — 토큰 경계 정렬된 '미친년' 은 채택 (원형 반환)")
	void checkWithTokens() throws Exception {
		mockMvc.perform(post("/banwords/check-with-tokens").contentType(MediaType.APPLICATION_JSON)
															.content("{\"text\":\"미친년\"}"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.success").value(true))
			   .andExpect(jsonPath("$.data.banWords.length()").value(1))
			   .andExpect(jsonPath("$.data.banWords[0]").value("미친년"));
	}

	@Test
	@DisplayName("admin/ban + evict + check — Trie 가 추가 금지어 반영")
	void adminBanThenCheck() throws Exception {
		String newWord = "테스트금지";

		// 1차 check — 아직 등록 안됨 → 0건
		mockMvc.perform(post("/banwords/check").contentType(MediaType.APPLICATION_JSON)
												.content("{\"text\":\"" + newWord + " 어쩌구\"}"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.data.banWords.length()").value(0));

		// admin 으로 추가 (evict 는 서비스가 호출)
		mockMvc.perform(post("/banwords/admin/ban").contentType(MediaType.APPLICATION_JSON)
													.content("{\"word\":\"" + newWord + "\"}"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.success").value(true));

		// 명시적 evict 한 번 더 — invalidate idempotent 검증
		mockMvc.perform(post("/banwords/evict"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.success").value(true));

		// 2차 check — Trie 재구성되어 매칭
		mockMvc.perform(post("/banwords/check").contentType(MediaType.APPLICATION_JSON)
												.content("{\"text\":\"" + newWord + " 어쩌구\"}"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.data.banWords[0]").value(newWord));
	}

	@Test
	@DisplayName("빈 text 는 400 — @NotBlank 위반")
	void emptyTextRejected() throws Exception {
		mockMvc.perform(post("/banwords/check").contentType(MediaType.APPLICATION_JSON)
												.content("{\"text\":\"\"}"))
			   .andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("10001 자 text 는 400 — @Size(max=10000) 위반")
	void tooLongTextRejected() throws Exception {
		String longText = "가".repeat(10_001);
		mockMvc.perform(post("/banwords/check").contentType(MediaType.APPLICATION_JSON)
												.content("{\"text\":\"" + longText + "\"}"))
			   .andExpect(status().isBadRequest());
	}
}
