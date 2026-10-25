package com.corelib.excel;

import com.corelib.common.exception.CoreException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 쓰기·읽기 (RFC 4180) — DTO 의 @ExcelColumn 을 xlsx 와 같이 쓴다.
 * <ul>
 *   <li>쓰기는 UTF-8 + BOM — 엑셀에서 열어도 한글이 깨지지 않는다. OutputStream 오버로드는 byte[] 사본 없이 바로 쓴다</li>
 *   <li>수식 주입 방지: String 열의 값이 = + - @ 탭·CR 로 시작하면 앞에 ' 를 붙인다 (엑셀이 =HYPERLINK(...) 등을 실행하지 않게).
 *       숫자·날짜 열은 그대로 (-5 는 -5)</li>
 *   <li>읽기는 스트리밍 — BOM 을 떼고, 따옴표 안의 쉼표·줄바꿈·"" 를 처리한다. 한 행 길이({@value #MAX_RECORD_LENGTH}자)·행 수 상한을 읽으면서 검사한다</li>
 * </ul>
 */
public final class CsvUtils {

	public static final String CONTENT_TYPE = "text/csv; charset=UTF-8";
	/** 한 행(따옴표 안 줄바꿈 포함) 최대 글자 수 — 줄바꿈 없는 거대한 한 줄로 메모리를 채우지 못하게 */
	static final int MAX_RECORD_LENGTH = 64 * 1024;
	private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
	private static final String LINE = "\r\n";

	private CsvUtils() {
	}

	/** 목록 → CSV 바이트 (UTF-8 BOM, CRLF) */
	public static <T> byte[] write(List<T> rows, Class<T> type) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(rows, type, out);
		return out.toByteArray();
	}

	/** 목록 → CSV 를 out(응답 스트림 등)에 바로 쓴다 — byte[] 사본을 만들지 않는다. out 은 닫지 않는다 */
	public static <T> void write(Iterable<T> rows, Class<T> type, OutputStream out) {
		List<ColumnMapping.Column> columns = ColumnMapping.columns(type);
		try {
			out.write(BOM);
			Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
			writeLine(writer, columns.stream().map(column -> escapeFormula(column.header())).toList());
			for (T row : rows) {
				writeLine(writer, columns.stream().map(column -> text(column, row)).toList());
			}
			writer.flush();
		} catch (IOException e) {
			throw new CoreException(ExcelError.MAPPING_FAILED, "CSV 파일을 만들지 못했습니다.", e);
		}
	}

	/**
	 * CSV(UTF-8) → 목록. 1행은 헤더, 헤더 이름으로 열을 찾는다.
	 * @throws CoreException EXCEL-INVALID (헤더 없음, 필수 열 없음, 행 수 초과, 한 행이 너무 김, 닫히지 않은 따옴표)
	 */
	public static <T> ExcelDto.ReadResult<T> read(InputStream content, Class<T> type) {
		List<ColumnMapping.Column> columns = ColumnMapping.columns(type);
		int maxRows = ExcelUtils.maxRows();
		int maxScanRows = ExcelUtils.maxScanRows();
		List<T> rows = new ArrayList<>();
		List<Integer> rowNumbers = new ArrayList<>();
		List<ExcelDto.RowError> errors = new ArrayList<>();
		int total = 0;
		try {
			RecordReader records = new RecordReader(new BufferedReader(new InputStreamReader(content, StandardCharsets.UTF_8)));
			List<String> header = records.next();
			if (header == null) {
				throw new CoreException(ExcelError.INVALID_FILE, "헤더 행이 없습니다.");
			}
			Map<String, Integer> headerIndex = new HashMap<>();
			for (int i = 0; i < header.size(); i++) {
				headerIndex.putIfAbsent(header.get(i).trim(), i);
			}
			ExcelUtils.requireHeaders(columns, headerIndex);

			int rowNumber = 1;
			for (List<String> record = records.next(); record != null; record = records.next()) {
				if (++rowNumber > maxScanRows) {
					throw new CoreException(ExcelError.INVALID_FILE, "행이 너무 많습니다 (빈 행 포함 최대 " + maxScanRows + "행).");
				}
				if (record.stream().allMatch(String::isBlank)) {
					continue;
				}
				if (++total > maxRows) {
					throw new CoreException(ExcelError.INVALID_FILE, "행이 너무 많습니다 (최대 " + maxRows + "행).");
				}
				T target = ColumnMapping.newRow(type);
				boolean isValid = true;
				for (ColumnMapping.Column column : columns) {
					Integer index = headerIndex.get(column.header());
					String value = index == null || index >= record.size() ? null : record.get(index);
					try {
						column.set(target, ColumnMapping.parse(isText(column) && value != null ? unescapeFormula(value) : value, column));
					} catch (IllegalArgumentException e) {
						isValid = false;
						errors.add(ExcelDto.RowError.builder().rowNumber(rowNumber).column(column.header()).message(e.getMessage()).build());
					}
				}
				if (isValid) {
					rows.add(target);
					rowNumbers.add(rowNumber);
				}
			}
		} catch (IOException e) {
			throw new CoreException(ExcelError.INVALID_FILE, "CSV 를 읽지 못했습니다.", e);
		}
		return ExcelDto.ReadResult.<T>builder().rows(rows).rowNumbers(rowNumbers).errors(errors).totalRows(total).build();
	}

	/** 수식 이스케이프는 String 열만 — 숫자·날짜 열의 -5 · -1.5 는 그대로 */
	private static boolean isText(ColumnMapping.Column column) {
		return column.type() == String.class;
	}

	private static String text(ColumnMapping.Column column, Object row) {
		String value = ColumnMapping.toText(column.get(row), column.annotation());
		return isText(column) ? escapeFormula(value) : value;
	}

	private static void writeLine(Writer writer, List<String> values) throws IOException {
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				writer.write(',');
			}
			writer.write(quote(values.get(i)));
		}
		writer.write(LINE);
	}

	/** 엑셀이 수식으로 실행하는 시작 문자 — 앞에 ' 를 붙여 글자로 만든다 */
	static String escapeFormula(String value) {
		if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
			return "'" + value;
		}
		return value;
	}

	/** 우리가 붙인 ' 를 다시 뗀다 — 쓰고 읽으면 원래 값 */
	private static String unescapeFormula(String value) {
		if (value.length() > 1 && value.charAt(0) == '\'' && "=+-@\t\r".indexOf(value.charAt(1)) >= 0) {
			return value.substring(1);
		}
		return value;
	}

	private static String quote(String value) {
		if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
			return value;
		}
		return '"' + value.replace("\"", "\"\"") + '"';
	}

	/** RFC 4180 레코드를 하나씩 — 따옴표 안 쉼표·줄바꿈, "" 는 따옴표 하나. 한 행이 MAX_RECORD_LENGTH 를 넘으면 중단 */
	private static final class RecordReader {

		private static final int NONE = -2;

		private final Reader reader;
		private int pending = NONE;
		private int length;

		RecordReader(BufferedReader reader) throws IOException {
			reader.mark(1);
			if (reader.read() != '\uFEFF') {
				reader.reset();
			}
			this.reader = reader;
		}

		/** 다음 레코드, 끝이면 null */
		List<String> next() throws IOException {
			length = 0;
			int ch = read();
			if (ch == -1) {
				return null;
			}
			List<String> record = new ArrayList<>();
			StringBuilder field = new StringBuilder();
			boolean isQuoted = false;
			for (; ch != -1; ch = read()) {
				if (isQuoted) {
					if (ch != '"') {
						field.append((char) ch);
					} else {
						int next = read();
						if (next == '"') {
							field.append('"');
						} else {
							isQuoted = false;
							pending = next;
						}
					}
				} else if (ch == '"' && field.isEmpty()) {
					isQuoted = true;
				} else if (ch == ',') {
					record.add(field.toString());
					field.setLength(0);
				} else if (ch == '\n' || ch == '\r') {
					if (ch == '\r') {
						int next = read();
						if (next != '\n') {
							pending = next;
						}
					}
					record.add(field.toString());
					return record;
				} else {
					field.append((char) ch);
				}
			}
			if (isQuoted) {
				throw new CoreException(ExcelError.INVALID_FILE, "닫히지 않은 따옴표가 있습니다.");
			}
			record.add(field.toString());
			return record;
		}

		private int read() throws IOException {
			if (pending != NONE) {
				int ch = pending;
				pending = NONE;
				return ch;
			}
			if (++length > MAX_RECORD_LENGTH) {
				throw new CoreException(ExcelError.INVALID_FILE, "한 행이 너무 깁니다 (최대 " + MAX_RECORD_LENGTH + "자).");
			}
			return reader.read();
		}
	}
}
