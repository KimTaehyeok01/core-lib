package com.corelib.excel;

import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * 엑셀(xlsx) 쓰기·읽기 — DTO 의 @ExcelColumn 으로 열을 정한다.
 * <ul>
 *   <li>쓰기는 스트리밍(SXSSF) — 메모리에 100행만 두고 나머지는 임시 파일로 흘려 수십만 행도 쓴다. OutputStream 오버로드는 byte[] 사본 없이 바로 쓴다</li>
 *   <li>읽기도 스트리밍(SAX) — 첫 시트를 한 행씩 읽고, 헤더 이름으로 열을 찾는다 (순서 무관), 행별 오류를 모아 돌려준다</li>
 *   <li>읽기 행 수 상한(core.excel.max-rows, 기본 10,000)을 넘는 순간 멈춘다. 빈 행·서식만 있는 행도 행 번호 상한(max-rows×2+100)으로 멈춘다</li>
 *   <li>업로드 크기 상한은 호출측 multipart 설정이 필수 — 압축 폭탄은 {@link ExcelInitialize} 가 ZipSecureFile 비율·항목 크기 상한을 명시한다</li>
 *   <li>Long·BigDecimal 이 15자리를 넘으면 글자 칸으로 쓴다 (엑셀 숫자는 15자리까지만 정확)</li>
 * </ul>
 */
public final class ExcelUtils {

	public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
	private static final int STREAMING_WINDOW = 100;
	private static final int MAX_SHEET_NAME = 31;
	/** 엑셀 숫자는 15자리까지만 정확하다 — 넘는 Long·BigDecimal 은 글자 칸으로 쓴다 */
	private static final int EXCEL_DIGITS = 15;
	private static final long EXCEL_EXACT_LIMIT = 1_000_000_000_000_000L;

	private static volatile int maxRows = 10_000;

	private ExcelUtils() {
	}

	/** 기동 시 {@link ExcelInitialize} 가 core.excel.max-rows 를 넣는다 */
	static void initialize(int initializeMaxRows) {
		maxRows = initializeMaxRows;
	}

	static int maxRows() {
		return maxRows;
	}

	/** 빈 행·서식만 있는 행까지 센 행 번호 상한 — 빈 행 수백만 개로 읽기를 오래 붙잡지 못하게 */
	static int maxScanRows() {
		return (int) Math.min(Integer.MAX_VALUE, maxRows * 2L + 100);
	}

	/** 목록 → xlsx 바이트 (1행 헤더, 2행부터 데이터) */
	public static <T> byte[] write(List<T> rows, Class<T> type, String sheetName) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(rows, type, sheetName, out);
		return out.toByteArray();
	}

	/** 목록 → xlsx 를 out(응답 스트림 등)에 바로 쓴다 — byte[] 사본을 만들지 않는다. out 은 닫지 않는다 */
	public static <T> void write(Iterable<T> rows, Class<T> type, String sheetName, OutputStream out) {
		List<ColumnMapping.Column> columns = ColumnMapping.columns(type);
		// close 가 SXSSF 임시 파일까지 정리한다
		try (SXSSFWorkbook workbook = new SXSSFWorkbook(STREAMING_WINDOW)) {
			SXSSFSheet sheet = workbook.createSheet(safeSheetName(sheetName));
			Styles styles = new Styles(workbook, columns);
			Row header = sheet.createRow(0);
			for (int i = 0; i < columns.size(); i++) {
				Cell cell = header.createCell(i);
				cell.setCellValue(columns.get(i).header());
				cell.setCellStyle(styles.header);
				sheet.setColumnWidth(i, Math.min(columns.get(i).annotation().width(), 255) * 256);
			}
			sheet.createFreezePane(0, 1);
			int rowIndex = 1;
			for (T row : rows) {
				Row excelRow = sheet.createRow(rowIndex++);
				for (int i = 0; i < columns.size(); i++) {
					writeCell(excelRow.createCell(i), columns.get(i).get(row), styles.data.get(i));
				}
			}
			workbook.write(out);
		} catch (IOException e) {
			throw new CoreException(ExcelError.MAPPING_FAILED, "엑셀 파일을 만들지 못했습니다.", e);
		}
	}

	/** 빈 양식 — 헤더만 있는 업로드용 템플릿 */
	public static <T> byte[] template(Class<T> type, String sheetName) {
		return write(List.of(), type, sheetName);
	}

	/**
	 * xlsx 첫 시트 → 목록. 헤더(1행)에 @ExcelColumn header 가 없으면 그 열은 비어 있는 것으로 본다 (required 면 행마다 오류).
	 * 숫자 칸을 String 필드로 읽으면 지수 표기·.0 없는 숫자 글자 (선행 0 은 엑셀에서 텍스트 서식이어야 남는다).
	 * @throws CoreException EXCEL-INVALID (xlsx 아님, 헤더 없음, 행 수 초과, 압축 폭탄)
	 */
	public static <T> ExcelDto.ReadResult<T> read(InputStream content, Class<T> type) {
		return XlsxReader.read(content, type, ColumnMapping.columns(type), maxRows);
	}

	static void requireHeaders(List<ColumnMapping.Column> columns, Map<String, Integer> headerIndex) {
		List<String> missing = columns.stream().filter(column -> column.annotation().required() && !headerIndex.containsKey(column.header()))
									  .map(ColumnMapping.Column::header).toList();
		if (!missing.isEmpty()) {
			throw new CoreException(ExcelError.INVALID_FILE, "필수 열이 없습니다: " + String.join(", ", missing));
		}
	}

	private static void writeCell(Cell cell, Object value, CellStyle style) {
		if (style != null) {
			cell.setCellStyle(style);
		}
		if (value == null) {
			cell.setBlank();
		} else if (value instanceof Long number && (number >= EXCEL_EXACT_LIMIT || number <= -EXCEL_EXACT_LIMIT)) {
			// 엑셀 숫자는 15자리까지 — 넘으면 끝자리가 0 으로 바뀌므로 글자 칸으로 (다시 읽으면 원래 값)
			cell.setCellValue(String.valueOf(number));
		} else if (value instanceof BigDecimal decimal && decimal.stripTrailingZeros().precision() > EXCEL_DIGITS) {
			cell.setCellValue(decimal.toPlainString());
		} else if (value instanceof Number number) {
			cell.setCellValue(number.doubleValue());
		} else if (value instanceof Boolean bool) {
			cell.setCellValue(bool ? "Y" : "N");
		} else if (value instanceof LocalDateTime dateTime) {
			cell.setCellValue(dateTime);
		} else if (value instanceof LocalDate date) {
			cell.setCellValue(date);
		} else if (value instanceof Enum<?> constant) {
			cell.setCellValue(constant.name());
		} else {
			// 문자열 칸 — 엑셀이 수식으로 해석하지 않는다 (=SUM(...) 도 글자 그대로)
			cell.setCellValue(String.valueOf(value));
		}
	}

	/** 시트 이름 규칙: 31자, : \ / ? * [ ] 불가 */
	private static String safeSheetName(String name) {
		String value = StringUtils.defaultIfBlank(name, "Sheet1").replaceAll("[:\\\\/?*\\[\\]]", "_");
		return StringUtils.truncate(value, MAX_SHEET_NAME);
	}

	/** 헤더·열별 서식 — 셀마다 만들면 서식 수 상한(64,000)에 걸린다 */
	private static final class Styles {

		private final CellStyle header;
		private final List<CellStyle> data = new ArrayList<>();

		Styles(Workbook workbook, List<ColumnMapping.Column> columns) {
			header = workbook.createCellStyle();
			Font bold = workbook.createFont();
			bold.setBold(true);
			header.setFont(bold);
			header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
			header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
			for (ColumnMapping.Column column : columns) {
				String format = column.annotation().format();
				if (StringUtils.isBlank(format)) {
					format = column.type() == LocalDateTime.class ? "yyyy-mm-dd hh:mm:ss" : column.type() == LocalDate.class ? "yyyy-mm-dd" : null;
				}
				if (format == null) {
					data.add(null);
					continue;
				}
				CellStyle style = workbook.createCellStyle();
				style.setDataFormat(workbook.createDataFormat().getFormat(format));
				data.add(style);
			}
		}
	}
}
