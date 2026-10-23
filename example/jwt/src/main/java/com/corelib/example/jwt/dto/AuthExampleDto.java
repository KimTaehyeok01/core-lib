package com.corelib.example.jwt.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 회원 로그인 예제 데이터 전송 객체 */
public class AuthExampleDto {

	// === 가입 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class SignupRequest {

		@NotBlank
		@Email
		@Size(max = 100)
		private String email;

		@NotBlank
		@Size(min = 8, max = 64)
		private String password;

		@NotBlank
		@Size(max = 20)
		private String nickname;
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

	// === 갱신 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class RefreshRequest {

		@NotBlank
		private String refreshToken;
	}

	// === 회원 (저장 모델) — 실서비스는 회원 테이블 ===
	@Getter
	@Builder(toBuilder = true)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Member {

		private final String memberId;

		private final String email;

		private final String nickname;

		/** base64url(salt) */
		private final String passwordSalt;

		/** base64url(PBKDF2) */
		private final String passwordHash;

		private final List<String> roles;

		private final LocalDateTime joinedAt;
	}

	// === 내 정보 / 회원 목록 항목 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class MemberResponse {

		private final String memberId;

		/** 본인 조회는 원문, 관리자 목록은 마스킹 */
		private final String email;

		private final String nickname;

		private final List<String> roles;

		private final String joinedAt;
	}
}
