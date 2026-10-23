package com.corelib.example.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.example.excel.dto.MemberExcelExampleDto;
import com.corelib.excel.ExcelDto;
import com.corelib.excel.ExcelUtils;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)    // 테스트마다 초기 회원 3명으로
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class MemberExcelExampleTest {

	private static final String HEADER = "이름,이메일,휴대폰,등급,포인트,가입일,수신동의\r\n";

	private final MockMvc mockMvc;

	private static MockMultipartFile csv(String body) {
		return new MockMultipartFile("file", "upload.csv", "text/csv", (HEADER + body).getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("엑셀 다운로드 — 한글 파일명(filename*), 다시 읽으면 초기 회원 3명 원문 그대로")
	void exportXlsx() throws Exception {
		byte[] xlsx = mockMvc.perform(get("/api/members/export"))
							 .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
							 .andExpect(header().string("Content-Type", ExcelUtils.CONTENT_TYPE))
							 .andExpect(header().string("X-Content-Type-Options", "nosniff"))
							 .andReturn().getResponse().getContentAsByteArray();

		ExcelDto.ReadResult<MemberExcelExampleDto.MemberRow> read = ExcelUtils.read(new ByteArrayInputStream(xlsx), MemberExcelExampleDto.MemberRow.class);
		assertThat(read.getRows()).extracting(MemberExcelExampleDto.MemberRow::getEmail)
								  .containsExactly("hong@example.com", "kim@example.com", "lee@example.com");
	}

	@Test
	@DisplayName("CSV 다운로드 — BOM + 헤더, 양식은 헤더만")
	void exportCsvAndTemplate() throws Exception {
		byte[] csv = mockMvc.perform(get("/api/members/export").param("format", "csv")).andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(csv, StandardCharsets.UTF_8)).startsWith("﻿" + HEADER).contains("홍길동,hong@example.com");

		byte[] template = mockMvc.perform(get("/api/members/template")).andReturn().getResponse().getContentAsByteArray();
		assertThat(ExcelUtils.read(new ByteArrayInputStream(template), MemberExcelExampleDto.MemberRow.class).getTotalRows()).isZero();
	}

	@Test
	@DisplayName("일괄 등록 성공 — 2명 추가, 평균 포인트, 목록 5명(페이지 계산)")
	void importSuccess() throws Exception {
		mockMvc.perform(multipart("/api/members/import").file(csv("박민수,park@example.com,,VIP,\"1,000\",2026-10-20,Y\r\n"
															  + "최지우,choi@example.com,01055556666,BASIC,2001,20261021,N\r\n")))
			   .andExpect(jsonPath("$.data.importedCount").value(2))
			   .andExpect(jsonPath("$.data.averagePoint").value(1501));

		mockMvc.perform(get("/api/members/list").param("page", "1").param("size", "2"))
			   .andExpect(jsonPath("$.data.totalElements").value(5))
			   .andExpect(jsonPath("$.data.totalPages").value(3))
			   .andExpect(jsonPath("$.data.content[0].maskedName").value("이*희"))
			   .andExpect(jsonPath("$.data.content[1].maskedEmail").value("pa**@example.com"));
	}

	@Test
	@DisplayName("일괄 등록 실패 — 형식 오류·이미 가입·파일 안 중복이 하나라도 있으면 아무도 등록 안 함, 행 번호로 알림")
	void importRejected() throws Exception {
		mockMvc.perform(multipart("/api/members/import").file(csv(
					"정상,ok@example.com,,BASIC,0,2026-10-20,Y\r\n"
						+ ",bad,,GOLD,많음,2026-13-01,Y\r\n"
						+ "중복,hong@example.com,,BASIC,0,2026-10-20,Y\r\n"
						+ "다시,ok@example.com,,BASIC,0,2026-10-20,Y\r\n")))
			   .andExpect(jsonPath("$.data.importedCount").value(0))
			   .andExpect(jsonPath("$.data.totalRows").value(4))
			   .andExpect(jsonPath("$.data.errors[?(@.rowNumber == 3 && @.column == '이름')]").exists())
			   .andExpect(jsonPath("$.data.errors[?(@.rowNumber == 3 && @.column == '포인트')]").exists())
			   .andExpect(jsonPath("$.data.errors[?(@.rowNumber == 4)].message").value("이미 가입된 이메일입니다."))
			   .andExpect(jsonPath("$.data.errors[?(@.rowNumber == 5)].message").value("파일 안에서 중복된 이메일입니다."));
		mockMvc.perform(get("/api/members/list")).andExpect(jsonPath("$.data.totalElements").value(3));
	}

	@Test
	@DisplayName("xlsx·csv 아닌 파일 C400, 잘못된 page·size C400")
	void rejected() throws Exception {
		mockMvc.perform(multipart("/api/members/import").file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes())))
			   .andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/members/list").param("size", "1000")).andExpect(status().isBadRequest());
	}
}
