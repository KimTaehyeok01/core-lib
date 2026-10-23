package com.corelib.example.auth.otp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 2단계 인증 예제 데이터 전송 객체 */
public class OtpExampleDto {

	// === 등록 시작 요청 — 실서비스는 로그인한 회원 ID 를 쓴다 (예제라 요청으로 받는다) ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class SetupRequest {

		@NotBlank
		@Size(max = 50)
		private String memberId;

		/** OTP 앱에 보이는 계정 이름 (보통 이메일) */
		@NotBlank
		@Size(max = 100)
		private String accountName;
	}

	// === 등록 시작 응답 — 화면이 QR 을 그린다 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class SetupResponse {

		/** 수동 입력용 — 4자리씩 띄어 보여준다 */
		private final String manualKey;

		/** QR 내용 */
		private final String otpauthUri;
	}

	// === 코드 입력 (활성화·로그인 검증·해제 공용) ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class CodeRequest {

		@NotBlank
		private String memberId;

		@NotBlank
		@Pattern(regexp = "[0-9 ]{6,8}", message = "인증 코드는 숫자 6자리입니다.")
		private String code;
	}

	// === 회원별 OTP 상태 (저장 모델) — 실서비스는 회원 테이블 컬럼 ===
	@Getter
	@Builder(toBuilder = true)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class OtpState {

		private final String memberId;

		/** AES-GCM 으로 암호화한 비밀키 — 원문은 저장하지 않는다 */
		private final String encryptedSecret;

		/** 활성화 전(등록 중)이면 false */
		private final boolean isEnabled;

		/** 마지막으로 성공한 timeStep — 같은 코드 재사용 차단 */
		private final long lastUsedTimeStep;

		private final int failureCount;

		private final LocalDateTime lockedUntil;

		private final LocalDateTime enabledAt;
	}

	// === 상태 조회 응답 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class StatusResponse {

		private final String memberId;

		private final boolean isEnabled;

		private final String enabledAt;

		/** 잠겨 있으면 풀리는 시각 */
		private final String lockedUntil;
	}
}
