package com.corelib.example.crypto;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
class CustomerExampleTest {

	private final MockMvc mockMvc;

	private ResultActions postJson(String path, String json) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	@Test
	@DisplayName("가입 → 로그인(대소문자 무관) → 휴대폰 변경")
	void joinLoginChange() throws Exception {
		postJson("/api/customers/join", "{\"name\":\"홍길동\",\"email\":\"Hong@Example.com\",\"mobileNo\":\"010-1234-5678\",\"password\":\"pass-word-1\"}")
			.andExpect(jsonPath("$.data.maskedName").value("홍*동"))
			.andExpect(jsonPath("$.data.maskedEmail").value("Ho**@Example.com"))
			.andExpect(jsonPath("$.data.maskedMobileNo").value("010-****-5678"));

		postJson("/api/customers/login", "{\"email\":\" hong@EXAMPLE.com \",\"password\":\"pass-word-1\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.maskedName").value("홍*동"));

		postJson("/api/customers/change-mobile", "{\"email\":\"hong@example.com\",\"password\":\"pass-word-1\",\"mobileNo\":\"01099998888\"}")
			.andExpect(jsonPath("$.data.maskedMobileNo").value("010-****-8888"));
	}

	@Test
	@DisplayName("같은 이메일(대소문자만 다름) 재가입 - 409, 비밀번호 틀림·없는 이메일 - 같은 401")
	void duplicateAndLoginFailure() throws Exception {
		postJson("/api/customers/join", "{\"name\":\"김철수\",\"email\":\"kim@example.com\",\"mobileNo\":\"01011112222\",\"password\":\"pass-word-2\"}")
			.andExpect(status().isOk());
		postJson("/api/customers/join", "{\"name\":\"김철수\",\"email\":\"KIM@example.com\",\"mobileNo\":\"01011112222\",\"password\":\"pass-word-2\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CUSTOMER_EMAIL_DUPLICATED"));

		postJson("/api/customers/login", "{\"email\":\"kim@example.com\",\"password\":\"wrong-pass\"}")
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("CUSTOMER_LOGIN_FAILED"));
		postJson("/api/customers/login", "{\"email\":\"nobody@example.com\",\"password\":\"pass-word-2\"}")
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("CUSTOMER_LOGIN_FAILED"));
	}

	@Test
	@DisplayName("로그인 5번 실패 - 잠금(429), 맞는 비밀번호도 거부 / 없는 이메일도 똑같이 잠금")
	void loginLocked() throws Exception {
		postJson("/api/customers/join", "{\"name\":\"박잠금\",\"email\":\"lock@example.com\",\"mobileNo\":\"01033334444\",\"password\":\"pass-word-3\"}")
			.andExpect(status().isOk());
		for (String email : List.of("lock@example.com", "ghost-lock@example.com")) {
			for (int i = 1; i <= 5; i++) {
				postJson("/api/customers/login", "{\"email\":\"" + email + "\",\"password\":\"wrong-pass\"}")
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("CUSTOMER_LOGIN_FAILED"));
			}
			postJson("/api/customers/login", "{\"email\":\"" + email + "\",\"password\":\"pass-word-3\"}")
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("CUSTOMER_LOGIN_LOCKED"));
		}
	}

	@Test
	@DisplayName("저장 형태 확인 API - 기본(example.crypto.debug-endpoints=false)은 없는 경로와 같은 404")
	void storedRowsHiddenByDefault() throws Exception {
		mockMvc.perform(get("/api/customers/stored-rows"))
			   .andExpect(status().isNotFound())
			   .andExpect(jsonPath("$.code").value("C404"));
	}

	@Test
	@DisplayName("형식 오류 - 휴대폰·이메일·짧은 비밀번호는 C400")
	void validation() throws Exception {
		postJson("/api/customers/join", "{\"name\":\"이영희\",\"email\":\"not-email\",\"mobileNo\":\"1234\",\"password\":\"short\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("C400"));
	}
}
