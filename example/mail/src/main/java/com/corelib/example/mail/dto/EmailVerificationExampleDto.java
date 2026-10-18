// 이메일 인증코드 예제 요청·응답 데이터
package com.corelib.example.mail.dto;

import jakarta.validation.constraints.Email;
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

/** 이메일 인증코드 예제 데이터 전송 객체 */
public class EmailVerificationExampleDto {

	// === 인증코드 발송 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class SendRequest {

		@NotBlank(message = "이메일을 입력하세요.")
		@Email(message = "이메일 형식이 아닙니다.")
		@Size(max = 100)
		private String email;
	}

	// === 발송 결과 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class SendResponse {

		/** 받는 사람 — 마스킹 */
		private final String maskedEmail;

		/** 코드 만료 시각 */
		private final LocalDateTime expiresAt;

		/** 이 시각 이후에 재발송할 수 있다 */
		private final LocalDateTime resendAvailableAt;
	}

	// === 인증 대기 (저장 모델) — 실서비스는 Redis(TTL) 나 인증 테이블 ===
	@Getter
	@Builder(toBuilder = true)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Pending {

		/** sha256(코드) — 코드 원문은 저장하지 않는다 */
		private final String codeHash;

		private final LocalDateTime sentAt;

		private final LocalDateTime expiresAt;

		/** 틀린 횟수 — 상한을 넘으면 코드 폐기 */
		private final Integer failCount;
	}

	// === 인증 확인 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class ConfirmRequest {

		@NotBlank
		@Email
		private String email;

		@NotBlank
		@Pattern(regexp = "[0-9]{6}", message = "인증코드는 숫자 6자리입니다.")
		private String code;
	}

	// === 인증 확인 결과 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class ConfirmResponse {

		private final String maskedEmail;

		private final LocalDateTime verifiedAt;
	}
}
