package com.corelib.lang;

/**
 * 개인정보 마스킹 — 화면 표시·로그·알림에 원문 대신 쓴다.
 * 빈값은 null, 형식이 짧아 일부만 남길 수 없으면 전부 가린다.
 */
public final class MaskingUtils {

	private static final String MASK = "*";

	private MaskingUtils() {
	}

	/** 홍길동 → 홍*동, 남궁민수 → 남**수, 이산 → 이*, 김 → * */
	public static String name(String name) {
		String value = StringUtils.trimToNull(name);
		if (value == null) {
			return null;
		}
		int length = value.codePointCount(0, value.length());
		if (length == 1) {
			return MASK;
		}
		int first = value.codePointAt(0);
		if (length == 2) {
			return new String(Character.toChars(first)) + MASK;
		}
		int last = value.codePointBefore(value.length());
		return new String(Character.toChars(first)) + MASK.repeat(length - 2) + new String(Character.toChars(last));
	}

	/** 01012345678 / 010-1234-5678 → 010-****-5678. 숫자가 10~11자리가 아니면 전부 가린다 */
	public static String mobile(String mobileNo) {
		if (StringUtils.isBlank(mobileNo)) {
			return null;
		}
		String digits = mobileNo.replaceAll("[^0-9]", "");
		if (digits.length() < 10 || digits.length() > 11) {
			return MASK.repeat(4);
		}
		return digits.substring(0, 3) + "-" + MASK.repeat(digits.length() - 7) + "-" + digits.substring(digits.length() - 4);
	}

	/** user@example.com → us**@example.com, a@b.com → *@b.com. @ 가 없으면 전부 가린다 */
	public static String email(String email) {
		String value = StringUtils.trimToNull(email);
		if (value == null) {
			return null;
		}
		int at = value.lastIndexOf('@');
		if (at <= 0) {
			return MASK.repeat(4);
		}
		String local = value.substring(0, at);
		int visible = local.length() <= 2 ? 0 : 2;
		return local.substring(0, visible) + MASK.repeat(local.length() - visible) + value.substring(at);
	}
}
