package com.corelib.crypto;

import com.corelib.common.exception.CoreException;
import java.util.Base64;

/** URL-safe 는 패딩 없이 인코딩하고, 디코딩은 패딩 유무를 모두 받는다 */
public final class Base64Utils {

	private Base64Utils() {
	}

	/** 표준 Base64 (패딩 포함) */
	public static String encode(byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	/**
	 * @throws CoreException INVALID_ENCODING
	 */
	public static byte[] decode(String value) {
		try {
			return Base64.getDecoder().decode(value);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new CoreException(CryptoError.INVALID_ENCODING, "Base64 형식이 올바르지 않습니다.");
		}
	}

	/** URL-safe Base64 (-, _ 사용, 패딩 없음) — 토큰·URL 파라미터용 */
	public static String encodeUrl(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * @throws CoreException INVALID_ENCODING
	 */
	public static byte[] decodeUrl(String value) {
		try {
			return Base64.getUrlDecoder().decode(value);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new CoreException(CryptoError.INVALID_ENCODING, "Base64URL 형식이 올바르지 않습니다.");
		}
	}
}
