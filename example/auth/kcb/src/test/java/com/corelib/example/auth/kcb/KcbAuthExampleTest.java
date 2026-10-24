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
import kcb.module.v3.OkCert;
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

	/** 팝업 시작 — 스텁이 mdlTkn 을 발급하고, 서버는 그 토큰을 세션에 묶는다 */
	private void start(MockHttpSession session, String mdlTkn) throws Exception {
		OkCert.nextMdlTkn = mdlTkn;
		mockMvc.perform(post("/api/kcb/start").session(session))
			   .andExpect(jsonPath("$.data.formFields.mdl_tkn").value(mdlTkn));
	}

	@Test
	@DisplayName("시작 → 콜백 → 가입, 같은 CI 로 다시 인증하면 같은 회원으로 로그인(번호 갱신), 결과는 마스킹")
	void joinThenLogin() throws Exception {
		MockHttpSession session = new MockHttpSession();

		OkCert.nextMdlTkn = "tkn-1";
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

		// 같은 콜백을 다시 열면 세션 토큰이 이미 소비돼 KCB 조회 없이 실패
		OkCert.lastSvcName = null;
		mockMvc.perform(get("/kcb/return").param("mdl_tkn", "tkn-1").session(session))
			   .andExpect(header().string("Location", startsWith("/done.html?result=fail")));
		assertThat(OkCert.lastSvcName).isNull();

		MockHttpSession again = new MockHttpSession();
		start(again, "tkn-again");
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
		start(session, "tkn-child");
		mockMvc.perform(get("/kcb/return").param("mdl_tkn", "tkn-child").session(session))
			   .andExpect(header().string("Location", startsWith("/done.html?result=fail")));
		mockMvc.perform(get("/api/kcb/result").session(session))
			   .andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("잘못된 토큰 - KCB 거절을 실패 페이지로")
	void invalidToken() throws Exception {
		MockHttpSession session = new MockHttpSession();
		start(session, "bad");
		mockMvc.perform(get("/kcb/return").param("mdl_tkn", "bad").session(session))
			   .andExpect(status().isFound())
			   .andExpect(header().string("Location", startsWith("/done.html?result=fail")));
	}

	@Test
	@DisplayName("start 없이 온 콜백 - KCB 결과 조회 없이 실패 페이지, 결과 없음")
	void callbackWithoutStart() throws Exception {
		MockHttpSession session = new MockHttpSession();
		OkCert.lastSvcName = null;

		mockMvc.perform(get("/kcb/return").param("mdl_tkn", "tkn-1").session(session))
			   .andExpect(header().string("Location", startsWith("/done.html?result=fail")));
		assertThat(OkCert.lastSvcName).isNull();
		mockMvc.perform(get("/api/kcb/result").session(session))
			   .andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("다른 세션이 발급받은 토큰(로그인 CSRF) - KCB 결과 조회 없이 실패, 세션 토큰은 그때 소비")
	void tokenFromOtherSession() throws Exception {
		MockHttpSession attacker = new MockHttpSession();
		MockHttpSession victim = new MockHttpSession();
		start(attacker, "tkn-1");
		start(victim, "tkn-again");

		// 공격자가 자기 세션에서 받은 토큰으로 피해자 브라우저를 콜백시킨다
		mockMvc.perform(get("/kcb/return").param("mdl_tkn", "tkn-1").session(victim))
			   .andExpect(header().string("Location", startsWith("/done.html?result=fail")));
		assertThat(OkCert.lastSvcName).isEqualTo("IDS_HS_POPUP_START");
		mockMvc.perform(get("/api/kcb/result").session(victim))
			   .andExpect(status().isNotFound());

		// 불일치 때 이미 소비했으므로 피해자 세션의 원래 토큰도 다시 쓸 수 없다
		mockMvc.perform(get("/kcb/return").param("mdl_tkn", "tkn-again").session(victim))
			   .andExpect(header().string("Location", startsWith("/done.html?result=fail")));
		assertThat(OkCert.lastSvcName).isEqualTo("IDS_HS_POPUP_START");
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
