package com.corelib.jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** jwt 요청·응답 데이터 */
public class JwtDto {

	/** 토큰 종류 — claim "typ" */
	public enum TokenType {
		/** API 호출용 — 짧게 (기본 30분) */
		ACCESS,
		/** 액세스 토큰 갱신용 — 길게 (기본 14일), API 호출에는 못 쓴다 */
		REFRESH
	}

	// === 로그인·갱신 응답 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class TokenPair {

		/** 항상 "Bearer" */
		private final String tokenType;

		private final String accessToken;

		/** 액세스 토큰 남은 시간(초) */
		private final long accessTokenExpiresIn;

		private final String refreshToken;

		private final long refreshTokenExpiresIn;

		/** 리프레시 토큰 jti — 폐기 목록·회전(rotation) 저장에 쓴다 */
		private final String refreshTokenId;
	}

	// === 검증 결과 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Claims {

		/** sub — 보통 회원 ID */
		private final String subject;

		private final TokenType tokenType;

		/** 권한 (예: USER, ADMIN) — Security 에서는 ROLE_ 접두로 붙는다 */
		private final List<String> roles;

		/** jti — 토큰마다 고유 */
		private final String tokenId;

		private final Instant issuedAt;

		private final Instant expiresAt;

		/** 발급 때 넣은 추가 claim */
		private final Map<String, Object> extra;
	}
}
