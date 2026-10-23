package com.corelib.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 비밀번호 저장용이 아니다 — 비밀번호는 BCrypt/Argon2 같은 전용 해시를 쓴다 */
public final class HashUtils {

	private HashUtils() {
	}

	public static byte[] sha256(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 은 모든 JDK 필수 알고리즘이라 도달하지 않는다
			throw new IllegalStateException("SHA-256 미지원 JDK", e);
		}
	}

	/** UTF-8 문자열의 SHA-256 을 소문자 hex 로 */
	public static String sha256Hex(String value) {
		return HexFormat.of().formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
	}
}
