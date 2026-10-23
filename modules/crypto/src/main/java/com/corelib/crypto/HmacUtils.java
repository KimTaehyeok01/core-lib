package com.corelib.crypto;

import com.corelib.common.exception.CoreException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacUtils {

	private static final String HMAC_SHA256 = "HmacSHA256";

	private HmacUtils() {
	}

	/**
	 * @throws CoreException INVALID_KEY(빈 키)
	 */
	public static byte[] hmacSha256(byte[] key, byte[] message) {
		if (key == null || key.length == 0) {
			throw new CoreException(CryptoError.INVALID_KEY, "HMAC 키가 비어 있습니다.");
		}
		try {
			Mac mac = Mac.getInstance(HMAC_SHA256);
			mac.init(new SecretKeySpec(key, HMAC_SHA256));
			return mac.doFinal(message);
		} catch (GeneralSecurityException e) {
			throw new CoreException(CryptoError.INVALID_KEY, "HMAC 생성에 실패했습니다.", e);
		}
	}

	/** 서명·MAC 비교는 반드시 이걸로 — equals 는 일치하는 앞부분 길이만큼 빨리 끝나 타이밍 공격에 노출된다 */
	public static boolean isEqual(byte[] expected, byte[] actual) {
		return expected != null && actual != null && MessageDigest.isEqual(expected, actual);
	}

	/** 문자열(hex·base64 등) 서명 비교 — 상수 시간 */
	public static boolean isEqual(String expected, String actual) {
		return expected != null && actual != null
			&& MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
	}
}
