package com.corelib.excel;

import com.corelib.common.exception.CoreException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 쓰기·읽기 (RFC 4180) — DTO 의 @ExcelColumn 을 xlsx 와 같이 쓴다.
 * <ul>
 *   <li>쓰기는 UTF-8 + BOM — 엑셀에서 열어도 한글이 깨지지 않는다</li>
 *   <li>수식 주입 방지: = + - @ 탭·CR 로 시작하는 값은 앞에 ' 를 붙인다 (엑셀이 =HYPERLINK(...) 등을 실행하지 않게)</li>
 *   <li>읽기는 BOM 을 떼고, 따옴표 안의 쉼표·줄바꿈·"" 를 처리한다</li>
 * </ul>
 */
public final class CsvUtils {

	public static final String CONTENT_TYPE = "text/csv; charset=UTF-8";
	private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
	private static final String LINE = "\r\n";

	private CsvUtils() {
	}

	/** 목록 → CSV 바이트 (UTF-8 BOM, CRLF) */
	public static <T> byte[] write(List<T> rows, Class<T> type) {
		List<ColumnMapping.Column> columns = ColumnMapping.columns(type);
		StringBuilder out = new StringBuilder();
		appendLine(out, columns.stream().map(ColumnMapping.Column::header).toList());
		for (T row : rows) {
			appendLine(out, columns.stream().map(column -> ColumnMapping.toText(column.get(row), column.annotation())).toList());
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.writeBytes(BOM);
		bytes.writeBytes(out.toString().getBytes(StandardCharsets.UTF_8));
		return bytes.toByteArray();
	}

	/**
	 * CSV(UTF-8) → 목록. 1행은 헤더, 헤더 이름으로 열을 찾는다.
	 * @throws CoreException EXCEL-INVALID (헤더 없음, 필수 열 없음, 행 수 초과, 닫히지 않은 따옴표)
	 */
	public static <T> ExcelDto.ReadResult<T> read(InputStream content, Class<T> type) {
		List<ColumnMapping.Column> columns = ColumnMapping.columns(type);
		List<List<String>> records;
		try {
			records = parse(stripBom(new String(content.readAllBytes(), StandardCharsets.UTF_8)), ExcelUtils.maxRows() + 1);
		} catch (IOException e) {
			throw new CoreException(ExcelError.INVALID_FILE, "CSV 를 읽지 못했습니다.", e);
		}
		if (records.isEmpty()) {
			throw new CoreException(ExcelError.INVALID_FILE, "헤더 행이 없습니다.");
		}
		Map<String, Integer> headerIndex = new HashMap<>();
		List<String> header = records.get(0);
		for (int i = 0; i < header.size(); i++) {
			headerIndex.putIfAbsent(header.get(i).trim(), i);
		}
		ExcelUtils.requireHeaders(columns, headerIndex);

		List<T> rows = new ArrayList<>();
		List<Integer> rowNumbers = new ArrayList<>();
		List<ExcelDto.RowError> errors = new ArrayList<>();
		int total = 0;
		for (int line = 1; line < records.size(); line++) {
			List<String> record = records.get(line);
			if (record.stream().allMatch(String::isBlank)) {
				continue;
			}
			total++;
			T target = ColumnMapping.newRow(type);
			boolean isValid = true;
			for (ColumnMapping.Column column : columns) {
				Integer index = headerIndex.get(column.header());
				String value = index == null || index >= record.size() ? null : unescapeFormula(record.get(index));
				try {
					column.set(target, ColumnMapping.parse(value, column));
				} catch (IllegalArgumentException e) {
					isValid = false;
					errors.add(ExcelDto.RowError.builder().rowNumber(line + 1).column(column.header()).message(e.getMessage()).build());
				}
			}
			if (isValid) {
				rows.add(target);
				rowNumbers.add(line + 1);
			}
		}
		return ExcelDto.ReadResult.<T>builder().rows(rows).rowNumbers(rowNumbers).errors(errors).totalRows(total).build();
	}

	private static void appendLine(StringBuilder out, List<String> values) {
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			out.append(quote(escapeFormula(values.get(i))));
		}
		out.append(LINE);
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

	private static String stripBom(String text) {
		return text.startsWith("﻿") ? text.substring(1) : text;
	}

	/** RFC 4180 — 따옴표 안 쉼표·줄바꿈, "" 는 따옴표 하나. maxRecords 를 넘으면 행 수 초과 */
	static List<List<String>> parse(String text, int maxRecords) {
		List<List<String>> records = new ArrayList<>();
		List<String> record = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean isQuoted = false;
		int i = 0;
		while (i < text.length()) {
			char ch = text.charAt(i);
			if (isQuoted) {
				if (ch == '"') {
					if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
						field.append('"');
						i += 2;
						continue;
					}
					isQuoted = false;
				} else {
					field.append(ch);
				}
				i++;
				continue;
			}
			if (ch == '"' && field.isEmpty()) {
				isQuoted = true;
			} else if (ch == ',') {
				record.add(field.toString());
				field.setLength(0);
			} else if (ch == '\n' || ch == '\r') {
				record.add(field.toString());
				field.setLength(0);
				records.add(record);
				record = new ArrayList<>();
				if (records.size() > maxRecords) {
					throw new CoreException(ExcelError.INVALID_FILE, "행이 너무 많습니다 (최대 " + (maxRecords - 1) + "행).");
				}
				if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
					i++;
				}
			} else {
				field.append(ch);
			}
			i++;
		}
		if (isQuoted) {
			throw new CoreException(ExcelError.INVALID_FILE, "닫히지 않은 따옴표가 있습니다.");
		}
		if (!field.isEmpty() || !record.isEmpty()) {
			record.add(field.toString());
			records.add(record);
		}
		return records;
	}
}
