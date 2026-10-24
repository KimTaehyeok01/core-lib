package com.corelib.example.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** example.crypto.debug-endpoints=true 일 때만 열리는 저장 형태 확인 API */
@SpringBootTest(properties = "example.crypto.debug-endpoints=true")
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class CustomerStoredRowsTest {

	private final MockMvc mockMvc;

	@Test
	@DisplayName("저장소에는 암호문·인덱스만 — 원문 없음, 비밀번호 해시도 응답에서 뺀다")
	void storedRows() throws Exception {
		mockMvc.perform(post("/api/customers/join").contentType(MediaType.APPLICATION_JSON)
												   .content("{\"name\":\"홍길동\",\"email\":\"Hong@Example.com\",\"mobileNo\":\"010-1234-5678\",\"password\":\"pass-word-1\"}"))
			   .andExpect(status().isOk());

		String stored = mockMvc.perform(get("/api/customers/stored-rows"))
							   .andExpect(status().isOk())
							   .andExpect(jsonPath("$.data[0].encryptedEmail").exists())
							   .andExpect(jsonPath("$.data[0].passwordHash").doesNotExist())
							   .andReturn().getResponse().getContentAsString();
		assertThat(stored).doesNotContain("Hong@Example.com", "hong@example.com", "01012345678", "pass-word-1");
	}
}
