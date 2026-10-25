package com.corelib.example.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import com.corelib.example.web.service.InquiryExampleService;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "example.admin.api-key=admin-test-key")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class InquiryExampleTest {

	private static final String BODY = "{\"name\":\"홍길동\",\"email\":\"hong@example.com\",\"content\":\"결제가 두 번 됐어요\"}";

	private final MockMvc mockMvc;
	private final List<StartupReporter> startupReporters;
	private final InquiryExampleService inquiryExampleService;

	private ResultActions create(String clientIp, String traceId) throws Exception {
		return mockMvc.perform(post("/api/inquiries/create").contentType(MediaType.APPLICATION_JSON).content(BODY)
															.header("X-Forwarded-For", clientIp)
															.header("X-Request-Id", traceId));
	}

	@Test
	@DisplayName("접수 — 추적번호를 돌려주고, 알림(@Async) 스레드 로그에도 같은 traceId, 요청 로그 이메일은 마스킹")
	void createWithTrace(CapturedOutput output) throws Exception {
		create("203.0.113.10", "inquiry-trace-0001")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.inquiryNo").isNumber())
			.andExpect(jsonPath("$.data.traceId").value("inquiry-trace-0001"));

		long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
		while (!output.getOut().contains("문의 접수 알림 발송") && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		String log = output.getOut();
		assertThat(log).containsPattern("\\[inquiry-trace-0001\\].*문의 접수 알림 발송.*thread=task-\\d+.*traceId=inquiry-trace-0001");
		assertThat(log).containsPattern("\\[inquiry-trace-0001\\].*POST /api/inquiries/create 200 \\d+ms ip=203\\.0\\.113\\.10");
		assertThat(log).contains("\"email\":\"***\"").doesNotContain("hong@example.com");
	}

	@Test
	@DisplayName("도배 방지 — 같은 IP 는 1분에 3건까지, 4번째는 429, 다른 IP 는 영향 없음")
	void rateLimit() throws Exception {
		for (int i = 0; i < 3; i++) {
			create("198.51.100.1", "rate-trace-000" + i).andExpect(status().isOk());
		}
		create("198.51.100.1", "rate-trace-0009")
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("INQUIRY_TOO_MANY"));
		create("198.51.100.2", "rate-trace-0010").andExpect(status().isOk());
	}

	@Test
	@DisplayName("관리자 — 키 없음·틀림 401 JSON, 맞으면 목록(이름·이메일 마스킹, IP·추적번호 포함)과 답변")
	void admin() throws Exception {
		create("203.0.113.20", "admin-trace-0001").andExpect(status().isOk());

		mockMvc.perform(get("/api/admin/inquiries/list"))
			   .andExpect(status().isUnauthorized())
			   .andExpect(jsonPath("$.code").value("C401"));
		mockMvc.perform(get("/api/admin/inquiries/list").header("X-Admin-Key", "wrong-key"))
			   .andExpect(status().isUnauthorized());

		mockMvc.perform(get("/api/admin/inquiries/list").header("X-Admin-Key", "admin-test-key"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.data[?(@.traceId == 'admin-trace-0001')].maskedEmail").value("ho**@example.com"))
			   .andExpect(jsonPath("$.data[?(@.traceId == 'admin-trace-0001')].maskedName").value("홍*동"))
			   .andExpect(jsonPath("$.data[?(@.traceId == 'admin-trace-0001')].clientIp").value("203.0.113.20"));

		String list = mockMvc.perform(get("/api/admin/inquiries/list").header("X-Admin-Key", "admin-test-key"))
							 .andReturn().getResponse().getContentAsString();
		Integer inquiryNo = com.jayway.jsonpath.JsonPath.read(list, "$.data[0].inquiryNo");
		mockMvc.perform(post("/api/admin/inquiries/answer").header("X-Admin-Key", "admin-test-key")
														   .contentType(MediaType.APPLICATION_JSON)
														   .content("{\"inquiryNo\":" + inquiryNo + ",\"answer\":\"환불 처리했습니다.\"}"))
			   .andExpect(jsonPath("$.success").value(true));
	}

	@Test
	@DisplayName("같은 문의에 답변 10건 동시 - 1건만 저장, 나머지는 409 INQUIRY_ALREADY_ANSWERED")
	void concurrentAnswer() throws Exception {
		String created = create("203.0.113.30", "answer-trace-0001").andReturn().getResponse().getContentAsString();
		Integer inquiryNo = JsonPath.read(created, "$.data.inquiryNo");

		int count = 10;
		ExecutorService executor = Executors.newFixedThreadPool(count);
		CountDownLatch ready = new CountDownLatch(count);
		CountDownLatch start = new CountDownLatch(1);
		List<Integer> statuses = new ArrayList<>();
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				String answer = "답변 " + i;
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return mockMvc.perform(post("/api/admin/inquiries/answer").header("X-Admin-Key", "admin-test-key")
																			   .contentType(MediaType.APPLICATION_JSON)
																			   .content("{\"inquiryNo\":" + inquiryNo + ",\"answer\":\"" + answer + "\"}"))
								  .andReturn().getResponse().getStatus();
				}));
			}
			ready.await(5, TimeUnit.SECONDS);
			start.countDown();
			for (Future<Integer> future : futures) {
				statuses.add(future.get(10, TimeUnit.SECONDS));
			}
		} finally {
			executor.shutdownNow();
		}

		assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
		assertThat(statuses).filteredOn(status -> status == 409).hasSize(count - 1);
		mockMvc.perform(post("/api/admin/inquiries/answer").header("X-Admin-Key", "admin-test-key")
														   .contentType(MediaType.APPLICATION_JSON)
														   .content("{\"inquiryNo\":" + inquiryNo + ",\"answer\":\"또 답변\"}"))
			   .andExpect(status().isConflict())
			   .andExpect(jsonPath("$.code").value("INQUIRY_ALREADY_ANSWERED"));
	}

	@Test
	@DisplayName("IP 접수 기록 정리 - 1분 지난 IP 키는 지우고, 최근 접수가 있는 IP 기록은 그대로 둬 제한 유지")
	@SuppressWarnings("unchecked")
	void evictIdleIps() throws Exception {
		Map<String, Deque<LocalDateTime>> recentByIp = (Map<String, Deque<LocalDateTime>>) ReflectionTestUtils.getField(inquiryExampleService, "recentByIp");
		recentByIp.put("192.0.2.99", new ArrayDeque<>(List.of(LocalDateTime.now().minusMinutes(2))));
		for (int i = 0; i < 3; i++) {
			create("192.0.2.100", "evict-trace-000" + i).andExpect(status().isOk());
		}

		inquiryExampleService.evictIdleIps();

		assertThat(recentByIp).doesNotContainKey("192.0.2.99").containsKey("192.0.2.100");
		create("192.0.2.100", "evict-trace-0009").andExpect(status().isTooManyRequests());
	}

	@Test
	@DisplayName("헬스체크·화면은 공개, 기동 안내에 Security 기본 체인과 Health 주소")
	void publicAndStartup() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(jsonPath("$.status").value("UP"));
		mockMvc.perform(get("/index.html")).andExpect(status().isOk());

		List<StartupReport> reports = startupReporters.stream().map(StartupReporter::report).toList();
		assertThat(reports).anySatisfy(report -> {
			assertThat(report.getName()).isEqualTo("Security");
			assertThat(report.getDetail()).contains("core 기본 체인", "추가 공개 경로 3개");
		});
		assertThat(reports).anySatisfy(report -> assertThat(report.getPath()).isEqualTo("/actuator/health"));
	}
}
