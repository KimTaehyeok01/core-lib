package com.corelib.auth.otp;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** OTP 응답 데이터 */
public class OtpDto {

	// === 등록 정보 — 화면이 otpauthUri 로 QR 을 그린다 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Registration {

		/** Base32 비밀키 — 호출측이 암호화해 저장한다. 화면에는 수동 입력용으로만 */
		private final String secret;

		/** otpauth://totp/... — QR 코드 내용 */
		private final String otpauthUri;
	}

	// === 검증 결과 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class VerifyResult {

		private final boolean isValid;

		/** 맞은 코드의 시간 단계 — 저장해 두고 다음 검증에 넘기면 같은 코드 재사용(replay)을 막는다. 실패면 -1 */
		private final long timeStep;
	}
}
