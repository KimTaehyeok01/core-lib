package com.corelib.excel;

import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 엑셀(xlsx) 쓰기·읽기 — DTO 의 @ExcelColumn 으로 열을 정한다.
 * <ul>
 *   <li>쓰기는 스트리밍(SXSSF) — 메모리에 100행만 두고 나머지는 임시 파일로 흘려 수십만 행도 쓴다</li>
 *   <li>읽기는 헤더 이름으로 열을 찾는다 (순서 무관), 행별 오류를 모아 돌려준다</li>
 *   <li>읽기 행 수 상한(core.excel.max-rows, 기본 10,000) — 업로드 크기 제한은 file 모듈·서블릿 설정으로</li>
 *   <li>압축 폭탄(작은 xlsx 가 거대하게 풀림)은 POI 기본 방어(ZipSecureFile)를 그대로 쓴다</li>
 * </ul>
 */
public final class ExcelUtils {

	public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
	private static final int STREAMING_WINDOW = 100;
	private static final int MAX_SHEET_NAME = 31;

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

	/** 목록 → xlsx 바이트 (1행 헤더, 2행부터 데이터) */
	public static <T> byte[] write(List<T> rows, Class<T> type, String sheetName) {
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
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			workbook.write(out);
			return out.toByteArray();
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
	 * @throws CoreException EXCEL-INVALID (xlsx 아님, 헤더 없음, 행 수 초과)
	 */
	public static <T> ExcelDto.ReadResult<T> read(InputStream content, Class<T> type) {
		List<ColumnMapping.Column> columns = ColumnMapping.columns(type);
		try (Workbook workbook = new XSSFWorkbook(content)) {
			Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
			Row header = sheet == null ? null : sheet.getRow(sheet.getFirstRowNum());
			if (header == null) {
				throw new CoreException(ExcelError.INVALID_FILE, "헤더 행이 없습니다.");
			}
			Map<String, Integer> headerIndex = new HashMap<>();
			DataFormatter formatter = new DataFormatter();
			header.forEach(cell -> headerIndex.putIfAbsent(StringUtils.defaultIfBlank(formatter.formatCellValue(cell), "").trim(), cell.getColumnIndex()));
			requireHeaders(columns, headerIndex);

			List<T> rows = new ArrayList<>();
		List<Integer> rowNumbers = new ArrayList<>();
			List<ExcelDto.RowError> errors = new ArrayList<>();
			int total = 0;
			for (int rowIndex = header.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
				Row row = sheet.getRow(rowIndex);
				if (isEmpty(row, formatter)) {
					continue;
				}
				if (++total > maxRows) {
					throw new CoreException(ExcelError.INVALID_FILE, "행이 너무 많습니다 (최대 " + maxRows + "행).");
				}
				T target = ColumnMapping.newRow(type);
				boolean isValid = true;
				for (ColumnMapping.Column column : columns) {
					Integer index = headerIndex.get(column.header());
					Cell cell = index == null ? null : row.getCell(index);
					try {
						column.set(target, readCell(cell, column, formatter));
					} catch (IllegalArgumentException e) {
						isValid = false;
						errors.add(ExcelDto.RowError.builder().rowNumber(rowIndex + 1).column(column.header()).message(e.getMessage()).build());
					}
				}
				if (isValid) {
					rows.add(target);
					rowNumbers.add(rowIndex + 1);
				}
			}
			return ExcelDto.ReadResult.<T>builder().rows(rows).rowNumbers(rowNumbers).errors(errors).totalRows(total).build();
		} catch (IOException | RuntimeException e) {
			if (e instanceof CoreException coreException) {
				throw coreException;
			}
			throw new CoreException(ExcelError.INVALID_FILE, "xlsx 파일이 아니거나 손상되었습니다.", e);
		}
	}

	static void requireHeaders(List<ColumnMapping.Column> columns, Map<String, Integer> headerIndex) {
		List<String> missing = columns.stream().filter(column -> column.annotation().required() && !headerIndex.containsKey(column.header()))
									  .map(ColumnMapping.Column::header).toList();
		if (!missing.isEmpty()) {
			throw new CoreException(ExcelError.INVALID_FILE, "필수 열이 없습니다: " + String.join(", ", missing));
		}
	}

	private static Object readCell(Cell cell, ColumnMapping.Column column, DataFormatter formatter) {
		if (cell == null) {
			return ColumnMapping.parse(null, column);
		}
		Class<?> type = column.type();
		CellType cellType = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
		// 엑셀이 날짜·숫자로 저장한 칸은 글자로 바꾸지 않고 값 그대로 읽는다
		if (cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
			LocalDateTime dateTime = cell.getLocalDateTimeCellValue();
			if (type == LocalDateTime.class) {
				return dateTime;
			}
			if (type == LocalDate.class) {
				return dateTime.toLocalDate();
			}
		}
		if (cellType == CellType.NUMERIC && (type == BigDecimal.class || type == Double.class || type == double.class)) {
			return type == BigDecimal.class ? BigDecimal.valueOf(cell.getNumericCellValue()) : cell.getNumericCellValue();
		}
		if (cellType == CellType.BOOLEAN && (type == Boolean.class || type == boolean.class)) {
			return cell.getBooleanCellValue();
		}
		return ColumnMapping.parse(formatter.formatCellValue(cell), column);
	}

	private static void writeCell(Cell cell, Object value, CellStyle style) {
		if (style != null) {
			cell.setCellStyle(style);
		}
		if (value == null) {
			cell.setBlank();
		} else if (value instanceof Number number && !(value instanceof BigDecimal)) {
			cell.setCellValue(number.doubleValue());
		} else if (value instanceof BigDecimal decimal) {
			cell.setCellValue(decimal.doubleValue());
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

	private static boolean isEmpty(Row row, DataFormatter formatter) {
		if (row == null) {
			return true;
		}
		for (Cell cell : row) {
			if (StringUtils.isNotBlank(formatter.formatCellValue(cell))) {
				return false;
			}
		}
		return true;
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
