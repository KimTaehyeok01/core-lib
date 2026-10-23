package com.corelib.crypto;

import com.corelib.common.exception.CoreException;
import java.util.HexFormat;

/** byte ↔ 16진수 문자열 — 출력은 소문자, 입력은 대소문자 모두 받는다 */
public final class HexUtils {

	private static final HexFormat HEX = HexFormat.of();

	private HexUtils() {
	}

	public static String encode(byte[] bytes) {
		return bytes == null ? null : HEX.formatHex(bytes);
	}

	/**
	 * @throws CoreException INVALID_ENCODING(홀수 길이·16진수 아닌 문자)
	 */
	public static byte[] decode(String hex) {
		if (hex == null) {
			return null;
		}
		try {
			return HEX.parseHex(hex.trim());
		} catch (IllegalArgumentException e) {
			throw new CoreException(CryptoError.INVALID_ENCODING, "16진수 문자열이 아닙니다.", e);
		}
	}
}
