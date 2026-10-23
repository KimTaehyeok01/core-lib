package com.corelib.auth.otp;

/** TOTP 해시 — Google Authenticator 는 SHA1 만 확실히 지원한다 */
public enum OtpAlgorithm {
	SHA1("HmacSHA1"),
	SHA256("HmacSHA256"),
	SHA512("HmacSHA512");

	private final String macName;

	OtpAlgorithm(String macName) {
		this.macName = macName;
	}

	String macName() {
		return macName;
	}
}
