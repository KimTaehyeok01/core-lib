package com.corelib.excel;

import com.corelib.common.exception.CoreException;
import com.corelib.lang.DateTimeUtils;
import com.corelib.lang.StringUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** @ExcelColumn 필드 목록과 값 변환 — xlsx·csv 공용 */
final class ColumnMapping {

	private static final Set<String> TRUE_TEXTS = Set.of("y", "yes", "true", "1", "예", "o");
	private static final Set<String> FALSE_TEXTS = Set.of("n", "no", "false", "0", "아니오", "x");

	private ColumnMapping() {
	}

	/** 열 하나 */
	record Column(Field field, ExcelColumn annotation, int declaredIndex) {

		String header() {
			return annotation.header();
		}

		Class<?> type() {
			return field.getType();
		}

		Object get(Object row) {
			try {
				return field.get(row);
			} catch (IllegalAccessException e) {
				throw new CoreException(ExcelError.MAPPING_FAILED, "필드를 읽을 수 없습니다: " + field.getName(), e);
			}
		}

		void set(Object row, Object value) {
			if (value == null && field.getType().isPrimitive()) {
				return;
			}
			try {
				field.set(row, value);
			} catch (IllegalAccessException e) {
				throw new CoreException(ExcelError.MAPPING_FAILED, "필드에 값을 넣을 수 없습니다: " + field.getName(), e);
			}
		}
	}

	/** 쓰기 순서(order → 선언 순서)로 정렬한 열 목록 */
	static List<Column> columns(Class<?> type) {
		List<Column> columns = new ArrayList<>();
		Field[] fields = type.getDeclaredFields();
		for (int i = 0; i < fields.length; i++) {
			ExcelColumn annotation = fields[i].getAnnotation(ExcelColumn.class);
			if (annotation != null) {
				requireSupported(fields[i]);
				fields[i].setAccessible(true);
				columns.add(new Column(fields[i], annotation, i));
			}
		}
		if (columns.isEmpty()) {
			throw new CoreException(ExcelError.MAPPING_FAILED, type.getSimpleName() + " 에 @ExcelColumn 필드가 없습니다.");
		}
		columns.sort(Comparator.comparingInt((Column column) -> column.annotation().order()).thenComparingInt(Column::declaredIndex));
		return columns;
	}

	/** 기본 생성자로 빈 행 — private 이어도 된다 (Lombok @NoArgsConstructor(access = PROTECTED) 등) */
	static <T> T newRow(Class<T> type) {
		try {
			Constructor<T> constructor = type.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (ReflectiveOperationException e) {
			throw new CoreException(ExcelError.MAPPING_FAILED, type.getSimpleName() + " 에 기본 생성자가 필요합니다.", e);
		}
	}

	/** 값 → 화면에 보일 글자 (CSV·읽기 오류 메시지용) */
	static String toText(Object value, ExcelColumn annotation) {
		if (value == null) {
			return "";
		}
		if (value instanceof LocalDateTime dateTime) {
			return dateTime.format(formatter(annotation, DateTimeUtils.DATE_TIME));
		}
		if (value instanceof LocalDate date) {
			return date.format(formatter(annotation, DateTimeUtils.DATE));
		}
		if (value instanceof BigDecimal decimal) {
			return decimal.toPlainString();
		}
		if (value instanceof Boolean bool) {
			return bool ? "Y" : "N";
		}
		if (value instanceof Enum<?> constant) {
			return constant.name();
		}
		return String.valueOf(value);
	}

	/**
	 * 글자 → 필드 타입. 빈 칸은 null.
	 * @throws IllegalArgumentException 형식 오류 — 메시지는 사용자에게 보여줄 문장
	 */
	static Object parse(String text, Column column) {
		String value = StringUtils.trimToNull(text);
		if (value == null) {
			if (column.annotation().required()) {
				throw new IllegalArgumentException("필수 값이 비어 있습니다.");
			}
			return null;
		}
		Class<?> type = column.type();
		try {
			if (type == String.class) {
				return value;
			}
			if (type == Integer.class || type == int.class) {
				return Integer.valueOf(number(value));
			}
			if (type == Long.class || type == long.class) {
				return Long.valueOf(number(value));
			}
			if (type == Double.class || type == double.class) {
				return Double.valueOf(number(value));
			}
			if (type == BigDecimal.class) {
				return new BigDecimal(number(value));
			}
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("숫자 형식이 아닙니다.");
		}
		if (type == Boolean.class || type == boolean.class) {
			String lower = value.toLowerCase(Locale.ROOT);
			if (TRUE_TEXTS.contains(lower)) {
				return true;
			}
			if (FALSE_TEXTS.contains(lower)) {
				return false;
			}
			throw new IllegalArgumentException("Y 또는 N 이어야 합니다.");
		}
		// 오류 메시지에 입력값을 넣지 않는다 — 생년월일 같은 개인정보일 수 있다
		try {
			if (type == LocalDate.class) {
				return StringUtils.isBlank(column.annotation().format()) ? DateTimeUtils.parseDate(value)
					: LocalDate.parse(value, DateTimeFormatter.ofPattern(column.annotation().format()));
			}
			if (type == LocalDateTime.class) {
				return StringUtils.isBlank(column.annotation().format()) ? DateTimeUtils.parseDateTime(value)
					: LocalDateTime.parse(value, DateTimeFormatter.ofPattern(column.annotation().format()));
			}
		} catch (IllegalArgumentException | DateTimeException e) {
			throw new IllegalArgumentException("날짜 형식이 아닙니다.");
		}
		if (type.isEnum()) {
			for (Object constant : type.getEnumConstants()) {
				if (((Enum<?>) constant).name().equalsIgnoreCase(value)) {
					return constant;
				}
			}
			throw new IllegalArgumentException("허용 값이 아닙니다.");
		}
		throw new IllegalArgumentException("지원하지 않는 타입입니다.");
	}

	/** 1,234 → 1234 (천 단위 쉼표 허용) */
	private static String number(String value) {
		return value.replace(",", "");
	}

	private static DateTimeFormatter formatter(ExcelColumn annotation, DateTimeFormatter fallback) {
		return StringUtils.isBlank(annotation.format()) ? fallback : DateTimeFormatter.ofPattern(annotation.format());
	}

	private static void requireSupported(Field field) {
		Class<?> type = field.getType();
		boolean isSupported = type == String.class || type == Integer.class || type == int.class || type == Long.class || type == long.class
			|| type == Double.class || type == double.class || type == BigDecimal.class || type == Boolean.class || type == boolean.class
			|| type == LocalDate.class || type == LocalDateTime.class || type.isEnum();
		if (!isSupported) {
			throw new CoreException(ExcelError.MAPPING_FAILED, "지원하지 않는 열 타입입니다: " + field.getName() + " (" + type.getSimpleName() + ")");
		}
	}
}
