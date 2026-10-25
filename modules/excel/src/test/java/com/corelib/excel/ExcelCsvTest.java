package com.corelib.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.exception.CoreException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
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

	@Getter
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	@AllArgsConstructor
	static class Account {

		@ExcelColumn(header = "휴대폰", order = 1)
		private String mobileNo;

		@ExcelColumn(header = "번호", order = 2)
		private Long no;

		@ExcelColumn(header = "금액", order = 3)
		private BigDecimal amount;
	}

	private static final String SHEET = "xl/worksheets/sheet1.xml";

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

	@Test
	@DisplayName("xlsx 행 상한 — maxRows+1 번째 데이터 행에서 바로 멈춘다 (그 뒤의 손상된 XML 까지 읽지 않음)")
	void xlsxStopsAtMaxRows() throws Exception {
		StringBuilder rows = new StringBuilder(textRow(1, "이름"));
		IntStream.rangeClosed(2, 5).forEach(r -> rows.append(textRow(r, "m" + r)));
		rows.append("<row r=\"6\"><c r=\"A6\" t=\"inlineStr\"><is><t>닫히지 않은 XML");
		byte[] xlsx = replaceEntry(workbook(new String[][] {{"이름"}}), SHEET, sheetXml(rows.toString()));

		ExcelUtils.initialize(3);
		assertThatThrownBy(() -> ExcelUtils.read(new ByteArrayInputStream(xlsx), Member.class))
			.isInstanceOf(CoreException.class).hasMessageContaining("최대 3행");

		ExcelUtils.initialize(10);
		assertThatThrownBy(() -> ExcelUtils.read(new ByteArrayInputStream(xlsx), Member.class))
			.isInstanceOf(CoreException.class).hasMessageContaining("손상");
	}

	@Test
	@DisplayName("xlsx 선두 빈 행 — 1행 공백·2행 헤더면 2행을 헤더로 삼아 데이터로 밀리지 않는다")
	void xlsxLeadingBlankHeader() throws Exception {
		byte[] xlsx;
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet("upload");
			sheet.createRow(0).createCell(0).setCellValue("");
			Row header = sheet.createRow(1);
			header.createCell(0).setCellValue("이름");
			header.createCell(1).setCellValue("나이");
			Row data = sheet.createRow(2);
			data.createCell(0).setCellValue("홍길동");
			data.createCell(1).setCellValue(30);
			workbook.write(out);
			xlsx = out.toByteArray();
		}

		ExcelDto.ReadResult<Member> result = ExcelUtils.read(new ByteArrayInputStream(xlsx), Member.class);

		assertThat(result.getTotalRows()).isEqualTo(1);
		assertThat(result.getRows()).hasSize(1);
		assertThat(result.getRows().get(0).getName()).isEqualTo("홍길동");
		assertThat(result.getRows().get(0).getAge()).isEqualTo(30);
		assertThat(result.getRowNumbers()).containsExactly(3);
	}

	@Test
	@DisplayName("xlsx 매칭 열 0 — 스키마의 어떤 헤더도 못 찾으면 INVALID_FILE")
	void xlsxNoMatchedHeader() throws Exception {
		byte[] xlsx = workbook(new String[][] {{"foo", "bar"}, {"1", "2"}});

		assertThatThrownBy(() -> ExcelUtils.read(new ByteArrayInputStream(xlsx), Account.class))
			.isInstanceOf(CoreException.class)
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(ExcelError.INVALID_FILE);
	}

	@Test
	@DisplayName("xlsx 빈 행 — 서식만 있는 행은 건너뛰되, 행 번호가 maxRows×2+100 을 넘으면 바로 거부")
	void xlsxFormattedEmptyRows() throws Exception {
		ExcelUtils.initialize(3);
		ExcelDto.ReadResult<Member> within = ExcelUtils.read(new ByteArrayInputStream(formattedEmptyRows(60, true)), Member.class);
		assertThat(within.getTotalRows()).isEqualTo(2);
		assertThat(within.getRowNumbers()).containsExactly(2, 63);

		assertThatThrownBy(() -> ExcelUtils.read(new ByteArrayInputStream(formattedEmptyRows(50_000, false)), Member.class))
			.isInstanceOf(CoreException.class).hasMessageContaining("빈 행 포함 최대 106행")
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(ExcelError.INVALID_FILE);
	}

	@Test
	@DisplayName("xlsx 압축 폭탄 — 100배 넘게 부풀어 오르는 항목은 ZipSecureFile 이 거부, 임시 파일은 남지 않음")
	void xlsxZipBomb() throws Exception {
		new ExcelInitialize(10_000);
		byte[] bomb = replaceEntry(workbook(new String[][] {{"이름"}}), SHEET,
			sheetXml(textRow(1, "이름") + "<!--" + " ".repeat(5_000_000) + "--></sheetData></worksheet>"));
		long before = tempFiles();

		assertThatThrownBy(() -> ExcelUtils.read(new ByteArrayInputStream(bomb), Member.class))
			.isInstanceOf(CoreException.class)
			.satisfies(e -> assertThat(((CoreException) e).getErrorCode()).isEqualTo(ExcelError.INVALID_FILE))
			.rootCause().hasMessageContaining("Zip bomb");
		ExcelUtils.read(new ByteArrayInputStream(ExcelUtils.write(MEMBERS, Member.class, "s")), Member.class);
		assertThat(tempFiles()).isEqualTo(before);
	}

	@Test
	@DisplayName("xlsx 중앙 디렉터리가 깨진 zip — POI 의 전체 메모리 해제(스트림 방식)로 넘어가지 않고 거부")
	void xlsxBrokenCentralDirectory() {
		byte[] xlsx = ExcelUtils.write(MEMBERS, Member.class, "s");
		byte[] truncated = java.util.Arrays.copyOf(xlsx, xlsx.length - 10);

		assertThatThrownBy(() -> ExcelUtils.read(new ByteArrayInputStream(truncated), Member.class))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(ExcelError.INVALID_FILE);
	}

	@Test
	@DisplayName("xlsx 숫자 칸 → String 필드는 지수 표기·.0 없이, Long·BigDecimal 은 값 그대로, 1E999999999 는 칸 오류")
	void xlsxNumericCells() throws Exception {
		byte[] xlsx = workbook(new Object[][] {
			{"휴대폰", "번호", "금액"},
			{1012345678, 30, 1234.5},
			{821012345678L, 123456789012345L, 0.1},
			{"01012345678", "", "1E999999999"}
		});

		ExcelDto.ReadResult<Account> result = ExcelUtils.read(new ByteArrayInputStream(xlsx), Account.class);

		assertThat(result.getRows()).extracting(Account::getMobileNo).containsExactly("1012345678", "821012345678");
		assertThat(result.getRows()).extracting(Account::getNo).containsExactly(30L, 123456789012345L);
		assertThat(result.getRows().get(0).getAmount()).isEqualByComparingTo("1234.5");
		assertThat(result.getRows().get(1).getAmount()).isEqualByComparingTo("0.1");
		assertThat(result.getErrors()).extracting(ExcelDto.RowError::getRowNumber, ExcelDto.RowError::getColumn, ExcelDto.RowError::getMessage)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(4, "금액", "숫자 범위를 벗어났습니다."));
	}

	@Test
	@DisplayName("xlsx 쓰기 — Long·BigDecimal 15자리 초과는 글자 칸, 15자리 이하는 숫자 칸, 다시 읽으면 원래 값")
	void xlsxLongDigits() throws Exception {
		List<Account> accounts = List.of(new Account("a", 1234567890123456789L, new BigDecimal("12345678901234567.89")),
			new Account("b", 123456789012345L, new BigDecimal("1234.50")));

		byte[] xlsx = ExcelUtils.write(accounts, Account.class, "s");

		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
			Sheet sheet = workbook.getSheetAt(0);
			assertThat(sheet.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.STRING);
			assertThat(sheet.getRow(1).getCell(2).getCellType()).isEqualTo(CellType.STRING);
			assertThat(sheet.getRow(2).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
			assertThat(sheet.getRow(2).getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
		}
		ExcelDto.ReadResult<Account> result = ExcelUtils.read(new ByteArrayInputStream(xlsx), Account.class);
		assertThat(result.getRows()).extracting(Account::getNo).containsExactly(1234567890123456789L, 123456789012345L);
		assertThat(result.getRows().get(0).getAmount()).isEqualTo(new BigDecimal("12345678901234567.89"));
	}

	@Test
	@DisplayName("xlsx 1904 날짜 체계(옛 Mac 엑셀) — 날짜가 4년 밀리지 않는다")
	void xlsxDate1904() throws Exception {
		byte[] xlsx;
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			workbook.getCTWorkbook().getWorkbookPr().setDate1904(true);
			CellStyle date = workbook.createCellStyle();
			date.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
			Sheet sheet = workbook.createSheet("upload");
			sheet.createRow(0).createCell(0).setCellValue("이름");
			sheet.getRow(0).createCell(1).setCellValue("가입일");
			sheet.createRow(1).createCell(0).setCellValue("홍길동");
			sheet.getRow(1).createCell(1).setCellValue(LocalDate.of(2026, 1, 2));
			sheet.getRow(1).getCell(1).setCellStyle(date);
			workbook.write(out);
			xlsx = out.toByteArray();
		}

		assertThat(ExcelUtils.read(new ByteArrayInputStream(xlsx), Member.class).getRows().get(0).getJoinedDt()).isEqualTo(LocalDate.of(2026, 1, 2));
	}

	@Test
	@DisplayName("OutputStream 오버로드 — byte[] 버전과 같은 내용, 호출측 스트림은 닫지 않음")
	void writeToOutputStream() throws Exception {
		CloseTrackingStream csv = new CloseTrackingStream();
		CsvUtils.write(MEMBERS, Member.class, csv);
		assertThat(csv.toByteArray()).isEqualTo(CsvUtils.write(MEMBERS, Member.class));
		assertThat(csv.isClosed).isFalse();

		CloseTrackingStream xlsx = new CloseTrackingStream();
		ExcelUtils.write(MEMBERS, Member.class, "회원", xlsx);
		assertThat(xlsx.isClosed).isFalse();
		assertThat(ExcelUtils.read(new ByteArrayInputStream(xlsx.toByteArray()), Member.class).getTotalRows()).isEqualTo(2);
	}

	@Test
	@DisplayName("CSV 수식 이스케이프는 String 열만 — 음수 -5·-1.50 은 그대로, 문자열 =1+1 은 '=1+1, 숫자 열의 ' 는 떼지 않음")
	void csvEscapesOnlyText() {
		List<Member> members = List.of(new Member("=1+1", -5, new BigDecimal("-1.50"), null, null, null, null, null),
			new Member("-5", null, null, null, null, null, null, null));

		String text = new String(CsvUtils.write(members, Member.class), StandardCharsets.UTF_8);
		assertThat(text).contains("'=1+1,-5,-1.50,", "'-5,");

		ExcelDto.ReadResult<Member> result = CsvUtils.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), Member.class);
		assertThat(result.getRows()).extracting(Member::getName).containsExactly("=1+1", "-5");
		assertThat(result.getRows().get(0).getAge()).isEqualTo(-5);
		assertThat(result.getRows().get(0).getPoint()).isEqualByComparingTo("-1.5");

		ExcelDto.ReadResult<Member> quoted = CsvUtils.read(new ByteArrayInputStream("이름,나이\na,'-5\n".getBytes(StandardCharsets.UTF_8)), Member.class);
		assertThat(quoted.getErrors()).extracting(ExcelDto.RowError::getMessage).containsExactly("숫자 형식이 아닙니다.");
	}

	@Test
	@DisplayName("CSV 스트리밍 거절 — 한 행 64K자 초과, 빈 행 포함 행 번호 상한 초과, BigDecimal 1E999999999 는 칸 오류")
	void csvStreamingLimits() {
		String longLine = "이름\n" + "a".repeat(CsvUtils.MAX_RECORD_LENGTH + 1) + "\n";
		assertThatThrownBy(() -> CsvUtils.read(new ByteArrayInputStream(longLine.getBytes(StandardCharsets.UTF_8)), Member.class))
			.isInstanceOf(CoreException.class).hasMessageContaining("한 행이 너무 깁니다");

		ExcelUtils.initialize(2);
		String blankRows = "이름,나이\na,1\n" + ",\n".repeat(200);
		assertThatThrownBy(() -> CsvUtils.read(new ByteArrayInputStream(blankRows.getBytes(StandardCharsets.UTF_8)), Member.class))
			.isInstanceOf(CoreException.class).hasMessageContaining("빈 행 포함 최대 104행");

		ExcelDto.ReadResult<Member> result = CsvUtils.read(
			new ByteArrayInputStream("이름,포인트\na,1E999999999\nb,\"1,234.5\"\n".getBytes(StandardCharsets.UTF_8)), Member.class);
		assertThat(result.getErrors()).extracting(ExcelDto.RowError::getRowNumber, ExcelDto.RowError::getMessage)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(2, "숫자 범위를 벗어났습니다."));
		assertThat(result.getRows().get(0).getPoint()).isEqualByComparingTo("1234.5");
	}

	/** 사용자가 직접 만든 엑셀 흉내 — String 은 글자 칸, Number 는 숫자 칸 */
	private static byte[] workbook(Object[][] cells) throws Exception {
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet("upload");
			for (int r = 0; r < cells.length; r++) {
				Row row = sheet.createRow(r);
				for (int c = 0; c < cells[r].length; c++) {
					if (cells[r][c] instanceof Number number) {
						row.createCell(c).setCellValue(number.doubleValue());
					} else {
						row.createCell(c).setCellValue((String) cells[r][c]);
					}
				}
			}
			workbook.write(out);
			return out.toByteArray();
		}
	}

	/** 헤더 + 데이터 1행 + 서식만 있는 빈 행 emptyRows 개 (+ 마지막 데이터 1행) */
	private static byte[] formattedEmptyRows(int emptyRows, boolean isLastRowData) throws Exception {
		try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			CellStyle style = workbook.createCellStyle();
			style.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
			style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
			Sheet sheet = workbook.createSheet("upload");
			sheet.createRow(0).createCell(0).setCellValue("이름");
			sheet.createRow(1).createCell(0).setCellValue("a");
			for (int r = 2; r < emptyRows + 2; r++) {
				Row row = sheet.createRow(r);
				row.setRowStyle(style);
				row.createCell(0).setCellStyle(style);
			}
			if (isLastRowData) {
				sheet.createRow(emptyRows + 2).createCell(0).setCellValue("b");
			}
			workbook.write(out);
			return out.toByteArray();
		}
	}

	/** xlsx 안의 파일 하나를 바꿔치기 — 손으로 만든 시트 XML·압축 폭탄 */
	private static byte[] replaceEntry(byte[] xlsx, String name, byte[] content) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(xlsx)); ZipOutputStream zip = new ZipOutputStream(out)) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				zip.putNextEntry(new ZipEntry(entry.getName()));
				zip.write(entry.getName().equals(name) ? content : in.readAllBytes());
				zip.closeEntry();
			}
		}
		return out.toByteArray();
	}

	private static byte[] sheetXml(String rows) {
		return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
			+ rows).getBytes(StandardCharsets.UTF_8);
	}

	private static String textRow(int rowNumber, String value) {
		return "<row r=\"" + rowNumber + "\"><c r=\"A" + rowNumber + "\" t=\"inlineStr\"><is><t>" + value + "</t></is></c></row>";
	}

	private static long tempFiles() throws Exception {
		try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
			return files.filter(file -> file.getFileName().toString().startsWith("core-excel-")).count();
		}
	}

	private static final class CloseTrackingStream extends ByteArrayOutputStream {

		private boolean isClosed;

		@Override
		public void close() {
			isClosed = true;
		}
	}
}
