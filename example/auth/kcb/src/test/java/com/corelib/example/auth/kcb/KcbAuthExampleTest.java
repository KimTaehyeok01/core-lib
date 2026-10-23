package com.corelib.example.auth.kcb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import java.util.List;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class KcbAuthExampleTest {

	@TempDir
	static Path licenseDir;

	private final MockMvc mockMvc;
	private final List<StartupReporter> startupReporters;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("auth.kcb.cp-cd", () -> "V00000000000");
		registry.add("auth.kcb.license-path", () -> {
			try {
				return Files.writeString(licenseDir.resolve("V00000000000_license.dat"), "license").toString();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	@Test
	@DisplayName("시작 → 콜백 → 가입, 같은 CI 로 다시 인증하면 같은 회원으로 로그인(번호 갱신), 결과는 마스킹")
	void joinThenLogin() throws Exception {
		MockHttpSession session = new MockHttpSession();

		mockMvc.perform(post("/api/kcb/start").session(session))
			   .andExpect(jsonPath("$.data.popupUrl").value("https://safe.ok-name.co.kr/CommonSvl"))
			   .andExpect(jsonPath("$.data.formFields.mdl_tkn").value("tkn-1"))
			   .andExpect(jsonPath("$.data.formFields.cp_cd").value("V00000000000"));
		mockMvc.perform(get("/kcb/return").param("mdl_tkn", "tkn-1").session(session))
			   .andExpect(redirectedUrl("/done.html?result=success"));
		String body = mockMvc.perform(get("/api/kcb/result").session(session))
							 .andExpect(jsonPath("$.data.isNewMember").value(true))
							 .andExpect(jsonPath("$.data.maskedName").value("홍*동"))
							 .andExpect(jsonPath("$.data.maskedTelNo").value("010-****-5678"))
							 .andReturn().getResponse().getContentAsString();
		Integer memberId = JsonPath.read(body, "$.data.memberId");

		MockHttpSession again = new MockHttpSession();
		mockMvc.perform(get("/kcb/return").param("mdl_tkn", "tkn-again").session(again))
			   .andExpect(redirectedUrl("/done.html?result=success"));
		mockMvc.perform(get("/api/kcb/result").session(again))
			   .andExpect(jsonPath("$.data.isNewMember").value(false))
			   .andExpect(jsonPath("$.data.memberId").value(memberId))
			   .andExpect(jsonPath("$.data.maskedTelNo").value("010-****-8888"));

		String members = mockMvc.perform(get("/api/kcb/members/list")).andReturn().getResponse().getContentAsString();
		assertThat(members).contains("\"memberId\":" + memberId).doesNotContain("01099998888", "홍길동", "CI-1");
	}

	@Test
	@DisplayName("만 14세 미만 - 가입 차단")
	void underAge() throws Exception {
		MockHttpSession session = new MockHttpSession();
		mockMvc.perform(get("/kcb/return").param("mdl_tkn", "tkn-child").session(session))
			   .andExpect(header().string("Location", startsWith("/done.html?result=fail")));
		mockMvc.perform(get("/api/kcb/result").session(session))
			   .andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("잘못된 토큰 - KCB 거절을 실패 페이지로")
	void invalidToken() throws Exception {
		mockMvc.perform(get("/kcb/return").param("mdl_tkn", "bad").session(new MockHttpSession()))
			   .andExpect(status().isFound())
			   .andExpect(header().string("Location", startsWith("/done.html?result=fail")));
	}

	@Test
	@DisplayName("기동 안내 - KcbAuthUtils 보고")
	void startupReport() {
		StartupReport report = startupReporters.stream().map(StartupReporter::report)
											   .filter(item -> item.getName().equals("KcbAuthUtils")).findFirst().orElseThrow();
		assertThat(report.isReady()).isEqualTo(true);
		assertThat(report.getDetail()).contains("OkCert jar 있음");
	}
}
