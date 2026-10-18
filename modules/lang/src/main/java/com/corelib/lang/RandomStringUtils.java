// 보안 난수(SecureRandom) 기반 무작위 문자열 생성 유틸
package com.corelib.lang;

import java.security.SecureRandom;

/** 요청번호·인증코드처럼 추측되면 안 되는 값에 쓴다 — java.util.Random 은 쓰지 않는다 */
public final class RandomStringUtils {

	private static final char[] NUMERIC = "0123456789".toCharArray();
	private static final char[] ALPHABETIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
	private static final char[] ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
	private static final SecureRandom RANDOM = new SecureRandom();

	private RandomStringUtils() {
	}

	/** 숫자만 (예: 휴대폰 인증번호) */
	public static String randomNumeric(int length) {
		return random(length, NUMERIC);
	}

	/** 영문 대소문자만 */
	public static String randomAlphabetic(int length) {
		return random(length, ALPHABETIC);
	}

	/** 영문 대소문자 + 숫자 */
	public static String randomAlphanumeric(int length) {
		return random(length, ALPHANUMERIC);
	}

	private static String random(int length, char[] chars) {
		if (length < 0) {
			throw new IllegalArgumentException("length 는 0 이상이어야 합니다.");
		}
		char[] result = new char[length];
		for (int i = 0; i < length; i++) {
			result[i] = chars[RANDOM.nextInt(chars.length)];
		}
		return new String(result);
	}
}
