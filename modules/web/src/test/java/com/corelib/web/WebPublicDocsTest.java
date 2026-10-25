package com.corelib.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = WebModuleIntegrationTest.TestApp.class, properties = "core.web.security.public-docs=true")
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class WebPublicDocsTest {

	private final MockMvc mockMvc;
	private final List<StartupReporter> startupReporters;

	@Test
	@DisplayName("public-docs=true — API 문서 경로는 인증 없이 열리고, actuator/info·나머지는 그대로 인증 필요")
	void docsOpened() throws Exception {
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.data").value("docs"));
		mockMvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("기동 안내 — API 문서 공개 표시")
	void startupReport() {
		StartupReport report = startupReporters.stream().map(StartupReporter::report)
											   .filter(item -> item.getName().equals("Security")).findFirst().orElseThrow();
		assertThat(report.getDetail()).contains("API 문서 공개").doesNotContain("비공개");
	}
}
