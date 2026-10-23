package com.corelib.lang;

public final class StringUtils {

	private StringUtils() {
	}

	/** null 이거나 공백(whitespace)만 있으면 true */
	public static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	/** 공백이 아닌 실제 내용이 있으면 true */
	public static boolean isNotBlank(String value) {
		return !isBlank(value);
	}

	/** 값이 비어 있으면 기본값으로 대체 */
	public static String defaultIfBlank(String value, String defaultValue) {
		return isBlank(value) ? defaultValue : value;
	}

	/** 앞뒤 공백을 지우고, 남는 게 없으면 null */
	public static String trimToNull(String value) {
		return isBlank(value) ? null : value.trim();
	}

	/** maxLength 를 넘으면 잘라낸다 — 로그·예외 메시지에 외부 값을 넣을 때 길이 상한용 */
	public static String truncate(String value, int maxLength) {
		if (maxLength < 0) {
			throw new IllegalArgumentException("maxLength 는 0 이상이어야 합니다.");
		}
		return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
	}
}
