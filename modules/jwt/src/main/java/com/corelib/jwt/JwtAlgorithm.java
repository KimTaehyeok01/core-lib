package com.corelib.jwt;

/** 서명 알고리즘 — 설정으로 하나만 고르고, 검증 때 토큰 헤더의 alg 가 이것과 같아야 한다 (알고리즘 바꿔치기 방지) */
public enum JwtAlgorithm {
	/** RSA-SHA256 — 개인키로 발급, 공개키(JWKS)로 누구나 검증. 기본 */
	RS256,
	/** HMAC-SHA256 — 비밀값 하나로 발급·검증. 비밀값을 아는 서비스끼리만 */
	HS256
}
