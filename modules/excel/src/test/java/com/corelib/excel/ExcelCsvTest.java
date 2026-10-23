package com.corelib.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.exception.CoreException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExcelCsvTest {

	enum Grade { BASIC, VIP }

	@Getter
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	@AllArgsConstructor
	static class Member {

		@ExcelColumn(header = "이름", order = 1, required = true)
		private String name;

		@ExcelColumn(header = "나이", order = 2)
		private Integer age;

		@ExcelColumn(header = "포인트", order = 3, format = "#,##0.00")
		private BigDecimal point;

		@ExcelColumn(header = "가입일", order = 4)
		private LocalDate joinedDt;

		@ExcelColumn(header = "최근접속", order = 5)
		private LocalDateTime lastLoginAt;

		@ExcelColumn(header = "등급", order = 6)
		private Grade grade;

		@ExcelColumn(header = "수신동의", order = 7)
		private Boolean isAgreed;

		/** @ExcelColumn 이 없으면 쓰지도 읽지도 않는다 */
		private String password;
	}

	static class Unsupported {

		@ExcelColumn(header = "목록")
		private List<String> values;
	}

	private static final List<Member> MEMBERS = List.of(
		new Member("홍길동", 30, new BigDecimal("1234.50"), LocalDate.of(2026, 1, 2), LocalDateTime.of(2026, 10, 24, 9, 30), Grade.VIP, true, "secret"),
		new Member("김, \"철수\"\n두줄", null, null, null, null, Grade.BASIC, false, null));

	@AfterEach
	void reset() {
		ExcelUtils.initialize(10_000);
	}

	@Test
	@DisplayName("xlsx 왕복 — 타입별 값이 그대로, @ExcelColumn 없는 필드는 빠짐")
	void xlsxRoundTrip() throws Exception {
		byte[] xlsx = ExcelUtils.write(MEMBERS, Member.class, "회원");

		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
			Row header = workbook.getSheet("회원").getRow(0);
			assertThat(header.getLastCellNum()).isEqualTo((short) 7);
			assertThat(header.getCell(0).getStringCellValue()).isEqualTo("이름");
		}
		ExcelDto.ReadResult<Member> result = ExcelUtils.read(new ByteArrayInputStream(xlsx), Member.class);

		assertThat(result.isSuccess()).isTrue();
		assertThat(result.getTotalRows()).isEqualTo(2);
		Member first = result.getRows().get(0);
		assertThat(first.getName()).isEqualTo("홍길동");
		assertThat(first.getAge()).isEqualTo(30);
		assertThat(first.getPoint()).isEqualByComparingTo("1234.5");
		assertThat(first.getJoinedDt()).isEqualTo(LocalDate.of(2026, 1, 2));
		assertThat(first.getLastLoginAt()).isEqualTo(LocalDateTime.of(2026, 10, 24, 9, 30));
		assertThat(first.getGrade()).isEqualTo(Grade.VIP);
		assertThat(first.getIsAgreed()).isTrue();
		assertThat(first.getPassword()).isNull();
		assertThat(result.getRows().get(1).getName()).isEqualTo("김, \"철수\"\n두줄");
	}

	@Test
	@DisplayName("xlsx 읽기 — 열 순서가 달라도 헤더 이름으로, 잘못된 칸은 행·열별 오류(값 원문 없음), 맞는 행은 담김")
	void xlsxReadErrors() throws Exception {
		byte[] xlsx = workbook(new String[][] {
			{"등급", "이름", "나이", "가입일"},
			{"VIP", "정상", "20", "2026-01-01"},
			{"GOLD", "", "스물", "1999-02-30"},
			{"", "", "", ""}
		});

		ExcelDto.ReadResult<Member> result = ExcelUtils.read(new ByteArrayInputStream(xlsx), Member.class);

		assertThat(result.getTotalRows()).isEqualTo(2);
		assertThat(result.getRows()).hasSize(1);
		assertThat(result.getRows().get(0).getGrade()).isEqualTo(Grade.VIP);
		assertThat(result.getRowNumbers()).containsExactly(2);
		assertThat(result.getErrors()).extracting(ExcelDto.RowError::getRowNumber, ExcelDto.RowError::getColumn, ExcelDto.RowError::getMessage)
			.containsExactlyInAnyOrder(
				org.assertj.core.groups.Tuple.tuple(3, "이름", "필수 값이 비어 있습니다."),
				org.assertj.core.groups.Tuple.tuple(3, "나이", "숫자 형식이 아닙니다."),
				org.assertj.core.groups.Tuple.tuple(3, "가입일", "날짜 형식이 아닙니다."),
				org.assertj.core.groups.Tuple.tuple(3, "등급", "허용 값이 아닙니다."));
		assertThat(result.getErrors()).noneMatch(error -> error.getMessage().contains("1999-02-30"));
	}

	@Test
	@DisplayName("xlsx 거절 — 필수 헤더 없음·행 수 초과·xlsx 아님은 EXCEL-INVALID, 지원 안 하는 타입은 EXCEL-MAPPING")
	void xlsxRejected() throws Exception {
		assertThatThrownBy(() -> ExcelUtils.read(new ByteArrayInputStream(workbook(new String[][] {{"나이"}, {"1"}})), Member.class))
			.isInstanceOf(CoreException.class).hasMessageContaining("필수 열이 없습니다: 이름");

		ExcelUtils.initialize(3);
		List<Member> many = IntStream.range(0, 5).mapToObj(i -> new Member("m" + i, i, null, null, null, null, null, null)).toList();
		assertThatThrownBy(() -> ExcelUtils.read(new ByteArrayInputStream(ExcelUtils.write(many, Member.class, "s")), Member.class))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(ExcelError.INVALID_FILE);

		assertThatThrownBy(() -> ExcelUtils.read(new ByteArrayInputStream("not xlsx".getBytes()), Member.class))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(ExcelError.INVALID_FILE);
		assertThatThrownBy(() -> ExcelUtils.write(List.of(), Unsupported.class, "s"))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(ExcelError.MAPPING_FAILED);
	}

	@Test
	@DisplayName("CSV 왕복 — UTF-8 BOM, 쉼표·따옴표·줄바꿈 이스케이프, 한글·날짜·Y/N 그대로")
	void csvRoundTrip() {
		byte[] csv = CsvUtils.write(MEMBERS, Member.class);

		assertThat(csv).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
		String text = new String(csv, StandardCharsets.UTF_8);
		assertThat(text).contains("이름,나이,포인트,가입일,최근접속,등급,수신동의\r\n",
			"홍길동,30,1234.50,2026-01-02,2026-10-24 09:30:00,VIP,Y\r\n", "\"김, \"\"철수\"\"\n두줄\"");

		ExcelDto.ReadResult<Member> result = CsvUtils.read(new ByteArrayInputStream(csv), Member.class);
		assertThat(result.isSuccess()).isTrue();
		assertThat(result.getRows().get(1).getName()).isEqualTo("김, \"철수\"\n두줄");
		assertThat(result.getRows().get(1).getIsAgreed()).isFalse();
		assertThat(result.getRows().get(0).getLastLoginAt()).isEqualTo(LocalDateTime.of(2026, 10, 24, 9, 30));
	}

	@Test
	@DisplayName("CSV 수식 주입 — = + - @ 로 시작하면 ' 를 붙여 글자로, 다시 읽으면 원래 값")
	void csvFormulaInjection() {
		List<Member> attack = List.of(new Member("=HYPERLINK(\"http://evil\",\"클릭\")", null, null, null, null, null, null, null),
			new Member("@SUM(A1)", null, null, null, null, null, null, null));

		String text = new String(CsvUtils.write(attack, Member.class), StandardCharsets.UTF_8);
		assertThat(text).contains("\"'=HYPERLINK(\"\"http://evil\"\",\"\"클릭\"\")\"", "'@SUM(A1)");

		ExcelDto.ReadResult<Member> result = CsvUtils.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), Member.class);
		assertThat(result.getRows().get(0).getName()).isEqualTo("=HYPERLINK(\"http://evil\",\"클릭\")");
	}

	@Test
	@DisplayName("CSV 거절 — 닫히지 않은 따옴표·빈 파일·행 수 초과는 EXCEL-INVALID")
	void csvRejected() {
		assertThatThrownBy(() -> CsvUtils.read(new ByteArrayInputStream("이름\n\"열린".getBytes(StandardCharsets.UTF_8)), Member.class))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(ExcelError.INVALID_FILE);
		assertThatThrownBy(() -> CsvUtils.read(new ByteArrayInputStream(new byte[0]), Member.class))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(ExcelError.INVALID_FILE);
		ExcelUtils.initialize(2);
		assertThatThrownBy(() -> CsvUtils.read(new ByteArrayInputStream("이름\na\nb\nc\n".getBytes(StandardCharsets.UTF_8)), Member.class))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(ExcelError.INVALID_FILE);
	}

	/** 사용자가 직접 만든 엑셀 흉내 — 모든 칸을 글자로 */
	private static byte[] workbook(String[][] cells) throws Exception {
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet("upload");
			for (int r = 0; r < cells.length; r++) {
				Row row = sheet.createRow(r);
				for (int c = 0; c < cells[r].length; c++) {
					row.createCell(c).setCellValue(cells[r][c]);
				}
			}
			workbook.write(out);
			return out.toByteArray();
		}
	}
}
