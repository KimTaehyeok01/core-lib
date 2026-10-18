// 개인정보 암호화 고객 예제 요청·응답 데이터
package com.corelib.example.crypto.dto;

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

/** 개인정보 암호화 고객 예제 데이터 전송 객체 */
public class CustomerExampleDto {

	// === 가입 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class JoinRequest {

		@NotBlank
		@Size(max = 30)
		private String name;

		@NotBlank
		@Email
		@Size(max = 100)
		private String email;

		@NotBlank
		@Pattern(regexp = "01[0-9]-?[0-9]{3,4}-?[0-9]{4}", message = "휴대폰번호 형식이 아닙니다.")
		private String mobileNo;

		@NotBlank
		@Size(min = 8, max = 64)
		private String password;
	}

	// === 고객 (저장 모델) — 실서비스는 고객 테이블. 개인정보는 암호문만, 원문은 저장하지 않는다 ===
	@Getter
	@Builder(toBuilder = true)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Customer {

		private final Long customerId;

		/** 이메일 검색 인덱스 — base64url(HMAC-SHA256(인덱스 키, 소문자 이메일)). 같은 이메일이면 같은 값 → unique 제약 */
		private final String emailIndex;

		/** base64url(IV || AES-256-GCM 암호문) */
		private final String encryptedName;

		private final String encryptedEmail;

		private final String encryptedMobileNo;

		/** base64url(salt) */
		private final String passwordSalt;

		/** base64url(PBKDF2-HmacSHA256(password, salt)) */
		private final String passwordHash;

		private final LocalDateTime joinedAt;
	}

	// === 고객 정보 (마스킹) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class CustomerResponse {

		private final Long customerId;

		private final String maskedName;

		private final String maskedEmail;

		private final String maskedMobileNo;

		private final LocalDateTime joinedAt;
	}

	// === 로그인 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class LoginRequest {

		@NotBlank
		private String email;

		@NotBlank
		private String password;
	}

	// === 휴대폰번호 변경 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class ChangeMobileRequest {

		@NotBlank
		private String email;

		@NotBlank
		private String password;

		@NotBlank
		@Pattern(regexp = "01[0-9]-?[0-9]{3,4}-?[0-9]{4}", message = "휴대폰번호 형식이 아닙니다.")
		private String mobileNo;
	}

	// === 저장된 모습 확인용 (관리 화면·디버깅) — 암호문만 나간다 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class StoredRow {

		private final Long customerId;

		private final String emailIndex;

		private final String encryptedEmail;

		private final String encryptedMobileNo;

		private final String passwordHash;
	}
}
