package com.corelib.payment.nice;

import com.corelib.crypto.HashUtils;
import com.corelib.crypto.HmacUtils;
import java.util.Locale;

/**
 * 매뉴얼의 서명 규칙 — 모두 {@code hex(sha256(값 이어붙임 + secretKey))}.
 * <ul>
 *   <li>인증결과(returnUrl): authToken + clientId + amount + secretKey</li>
 *   <li>승인/조회 응답: tid + amount + ediDate + secretKey</li>
 * </ul>
 */
final class NicePaySignature {

	private NicePaySignature() {
	}

	/** returnUrl 인증결과 서명 */
	static String ofAuthResult(String authToken, String clientId, Integer amount, String secretKey) {
		return HashUtils.sha256Hex(authToken + clientId + amount + secretKey);
	}

	/** 승인/조회 응답 서명 */
	static String ofPayment(String tid, Integer amount, String ediDate, String secretKey) {
		return HashUtils.sha256Hex(tid + amount + ediDate + secretKey);
	}

	/** 타이밍 공격을 피하려고 상수 시간으로 비교한다 (hex 대소문자 무시) */
	static boolean matches(String expected, String actual) {
		return expected != null && actual != null
			&& HmacUtils.isEqual(expected.toLowerCase(Locale.ROOT), actual.trim().toLowerCase(Locale.ROOT));
	}
}
