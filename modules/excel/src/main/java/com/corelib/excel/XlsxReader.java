package com.corelib.excel;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * xlsx 첫 시트를 SAX 로 한 행씩 읽는다 ({@link ExcelUtils#read}) — 시트를 메모리(DOM)에 올리지 않는다.
 * <ul>
 *   <li>업로드를 임시 파일에 두고 zip 항목을 읽을 때 푼다 — 전부 풀어 메모리에 올리지 않는다 (ZipSecureFile 비율·크기 검사도 그때)</li>
 *   <li>데이터 행이 maxRows 를 넘는 순간, 빈 행·서식만 있는 행까지 센 행 번호가 {@link ExcelUtils#maxScanRows()} 를 넘는 순간 멈춘다</li>
 *   <li>숫자 칸은 화면 서식 글자(1,234.00 · 1.01E+10)가 아니라 저장된 값으로 읽는다</li>
 * </ul>
 */
final class XlsxReader<T> implements XSSFSheetXMLHandler.SheetContentsHandler {

	/** 선두의 빈 행은 건너뛰고 헤더를 찾는다 — 그 뒤로는 아무 매칭도 없으면 거부 */
	private static final int MAX_HEADER_SCAN_ROWS = 5;

	private final Class<T> type;
	private final List<ColumnMapping.Column> columns;
	private final Set<String> headers = new HashSet<>();
	private final int maxRows;
	private final int maxScanRows;
	private final boolean isDate1904;
	private final ValueFormatter formatter;

	private final Map<String, Integer> headerIndex = new HashMap<>();
	/** 헤더 행을 다 읽기 전에는 null */
	private Set<Integer> mappedIndexes;
	private final Map<Integer, CellValue> cells = new HashMap<>();
	private boolean isBlankRow;
	private int lastColumn;
	/** 헤더 후보로 본 행 수 — 빈 행이면 다음 행을 다시 후보로 삼는다 */
	private int headerScanRows;

	private final List<T> rows = new ArrayList<>();
	private final List<Integer> rowNumbers = new ArrayList<>();
	private final List<ExcelDto.RowError> errors = new ArrayList<>();
	private int total;

	private XlsxReader(Class<T> type, List<ColumnMapping.Column> columns, int maxRows, boolean isDate1904) {
		this.type = type;
		this.columns = columns;
		this.maxRows = maxRows;
		this.maxScanRows = ExcelUtils.maxScanRows();
		this.isDate1904 = isDate1904;
		this.formatter = new ValueFormatter(isDate1904);
		columns.forEach(column -> headers.add(column.header()));
	}

	static <T> ExcelDto.ReadResult<T> read(InputStream content, Class<T> type, List<ColumnMapping.Column> columns, int maxRows) {
		Path file = createTempFile();
		try {
			Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING);
			// 중앙 디렉터리가 깨진 zip 은 POI 가 모든 항목을 메모리로 푸는 스트림 방식으로 넘어간다 — 그 전에 거부
			new ZipSecureFile(file.toFile()).close();
			OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ);
			try {
				XSSFReader reader = new XSSFReader(pkg);
				Iterator<InputStream> sheets = reader.getSheetsData();
				if (!sheets.hasNext()) {
					throw new CoreException(ExcelError.INVALID_FILE, "헤더 행이 없습니다.");
				}
				XlsxReader<T> handler = new XlsxReader<>(type, columns, maxRows, isDate1904(reader));
				StylesTable styles = reader.getStylesTable();
				XMLReader parser = XMLHelper.newXMLReader();
				parser.setContentHandler(new XSSFSheetXMLHandler(styles == null ? new StylesTable() : styles,
					new ReadOnlySharedStringsTable(pkg, false), handler, handler.formatter, false));
				try (InputStream sheet = sheets.next()) {
					parser.parse(new InputSource(sheet));
				}
				return handler.result();
			} finally {
				pkg.revert();
			}
		} catch (IOException | OpenXML4JException | SAXException | ParserConfigurationException | RuntimeException e) {
			if (e instanceof CoreException coreException) {
				throw coreException;
			}
			if (e instanceof SAXException sax && sax.getException() instanceof CoreException coreException) {
				throw coreException;
			}
			throw new CoreException(ExcelError.INVALID_FILE, "xlsx 파일이 아니거나 손상되었습니다.", e);
		} finally {
			delete(file);
		}
	}

	@Override
	public void startRow(int rowNum) {
		if (rowNum + 1 > maxScanRows) {
			throw new CoreException(ExcelError.INVALID_FILE, "행이 너무 많습니다 (빈 행 포함 최대 " + maxScanRows + "행).");
		}
		cells.clear();
		isBlankRow = true;
		lastColumn = -1;
	}

	@Override
	public void cell(String cellReference, String formattedValue, XSSFComment comment) {
		int column = cellReference == null ? lastColumn + 1 : new CellAddress(cellReference).getColumn();
		lastColumn = column;
		CellValue value = formatter.take(formattedValue);
		if (mappedIndexes == null) {
			if (StringUtils.isNotBlank(formattedValue)) {
				isBlankRow = false;
				String name = formattedValue.trim();
				if (headers.contains(name)) {
					headerIndex.putIfAbsent(name, column);
				}
			}
			return;
		}
		if (StringUtils.isNotBlank(formattedValue)) {
			isBlankRow = false;
		}
		if (mappedIndexes.contains(column)) {
			cells.put(column, value);
		}
	}

	@Override
	public void endRow(int rowNum) {
		if (mappedIndexes == null) {
			if (isBlankRow) {
				// 빈 헤더 후보 행 — 다음 행을 다시 후보로 삼는다, 처음 몇 행까지만 스캔
				if (++headerScanRows >= MAX_HEADER_SCAN_ROWS) {
					throw new CoreException(ExcelError.INVALID_FILE, "헤더 행이 없습니다.");
				}
				headerIndex.clear();
				return;
			}
			if (headerIndex.isEmpty()) {
				throw new CoreException(ExcelError.INVALID_FILE, "헤더 행이 없습니다.");
			}
			ExcelUtils.requireHeaders(columns, headerIndex);
			mappedIndexes = new HashSet<>(headerIndex.values());
			return;
		}
		if (isBlankRow) {
			return;
		}
		if (++total > maxRows) {
			throw new CoreException(ExcelError.INVALID_FILE, "행이 너무 많습니다 (최대 " + maxRows + "행).");
		}
		T target = ColumnMapping.newRow(type);
		boolean isValid = true;
		for (ColumnMapping.Column column : columns) {
			Integer index = headerIndex.get(column.header());
			try {
				column.set(target, readCell(index == null ? null : cells.get(index), column));
			} catch (IllegalArgumentException e) {
				isValid = false;
				errors.add(ExcelDto.RowError.builder().rowNumber(rowNum + 1).column(column.header()).message(e.getMessage()).build());
			}
		}
		if (isValid) {
			rows.add(target);
			rowNumbers.add(rowNum + 1);
		}
	}

	private ExcelDto.ReadResult<T> result() {
		if (mappedIndexes == null) {
			throw new CoreException(ExcelError.INVALID_FILE, "헤더 행이 없습니다.");
		}
		return ExcelDto.ReadResult.<T>builder().rows(rows).rowNumbers(rowNumbers).errors(errors).totalRows(total).build();
	}

	private Object readCell(CellValue cell, ColumnMapping.Column column) {
		if (cell == null || cell.number() == null) {
			return ColumnMapping.parse(cell == null ? null : cell.text(), column);
		}
		Class<?> type = column.type();
		double number = cell.number();
		// 엑셀이 날짜·숫자로 저장한 칸은 화면 서식 글자로 바꾸지 않고 값 그대로 읽는다
		if (cell.isDate() && (type == LocalDateTime.class || type == LocalDate.class)) {
			LocalDateTime dateTime = DateUtil.getLocalDateTime(number, isDate1904);
			return type == LocalDate.class ? dateTime.toLocalDate() : dateTime;
		}
		if (type == Double.class || type == double.class) {
			return number;
		}
		// String 도 지수 표기·.0 없이 — 선행 0(010…)은 숫자 칸에 남지 않는다, 엑셀에서 텍스트 서식이어야 한다
		if (type == BigDecimal.class
			|| !cell.isDate() && (type == String.class || type == Integer.class || type == int.class || type == Long.class || type == long.class)) {
			return ColumnMapping.parse(plain(number), column);
		}
		return ColumnMapping.parse(cell.text(), column);
	}

	/** 1.012345678E10 → 10123456780, 30.0 → 30 */
	private static String plain(double number) {
		if (!Double.isFinite(number)) {
			throw new IllegalArgumentException("숫자 형식이 아닙니다.");
		}
		return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
	}

	/** 1904 날짜 체계(옛 Mac 엑셀) — workbook.xml 의 workbookPr date1904 */
	private static boolean isDate1904(XSSFReader reader) throws IOException, OpenXML4JException, SAXException, ParserConfigurationException {
		boolean[] isDate1904 = {false};
		XMLReader parser = XMLHelper.newXMLReader();
		parser.setContentHandler(new DefaultHandler() {
			@Override
			public void startElement(String uri, String localName, String qName, Attributes attributes) {
				if ("workbookPr".equals(localName)) {
					String value = attributes.getValue("date1904");
					isDate1904[0] = "1".equals(value) || "true".equalsIgnoreCase(value);
				}
			}
		});
		try (InputStream workbook = reader.getWorkbookData()) {
			parser.parse(new InputSource(workbook));
		}
		return isDate1904[0];
	}

	private static Path createTempFile() {
		try {
			return Files.createTempFile("core-excel-", ".xlsx");
		} catch (IOException e) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "엑셀 임시 파일을 만들지 못했습니다.", e);
		}
	}

	private static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			file.toFile().deleteOnExit();
		}
	}

	/** 칸 하나 — 숫자 칸이면 number 에 저장 값, 아니면 null */
	private record CellValue(String text, Double number, boolean isDate) {
	}

	/**
	 * 숫자 칸의 저장 값을 잡아 둔다 — XSSFSheetXMLHandler 는 서식을 적용한 글자만 넘기므로.
	 * 핸들러가 숫자 칸마다 이 메서드를 부른 직후 cell() 을 부르고, cell() 에서 {@link #take} 로 꺼낸다.
	 */
	private static final class ValueFormatter extends DataFormatter {

		private final boolean isDate1904;
		private Double number;
		private boolean isDate;

		ValueFormatter(boolean isDate1904) {
			this.isDate1904 = isDate1904;
		}

		@Override
		public String formatRawCellContents(double value, int formatIndex, String formatString) {
			number = value;
			isDate = DateUtil.isADateFormat(formatIndex, formatString) && DateUtil.isValidExcelDate(value);
			return super.formatRawCellContents(value, formatIndex, formatString, isDate1904);
		}

		CellValue take(String text) {
			CellValue value = new CellValue(text, number, isDate);
			number = null;
			isDate = false;
			return value;
		}
	}
}
