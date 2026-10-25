package com.corelib.jwt;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.crypto.PemUtils;
import com.corelib.lang.CollectionUtils;
import com.corelib.lang.StringUtils;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * JWT(JWS) 발급·검증.
 * <ul>
 *   <li>무상태 — 리프레시 토큰 폐기(로그아웃)·회전은 호출측이 jti 로 저장해 확인한다</li>
 *   <li>검증: 길이 상한 → 헤더 alg 가 설정과 같은지 → 서명 → iss·aud → exp·nbf(시계 오차 허용) → 토큰 종류</li>
 *   <li>예외 메시지에 토큰 원문을 넣지 않는다</li>
 * </ul>
 */
@Slf4j
public final class JwtUtils {

	static final String TOKEN_TYPE_CLAIM = "token_type";
	static final String ROLES_CLAIM = "roles";
	private static final Set<String> RESERVED = Set.of("iss", "sub", "aud", "exp", "nbf", "iat", "jti", TOKEN_TYPE_CLAIM, ROLES_CLAIM);
	/** 헤더로 오는 토큰 길이 상한 — 거대한 토큰으로 파싱 부하를 주지 못하게 */
	private static final int MAX_TOKEN_LENGTH = 8 * 1024;

	private static volatile Keys keys;

	private JwtUtils() {
	}

	/** 기동 시 {@link JwtInitialize} 가 1회 호출한다 */
	static void initialize(JwtProperty property) {
		property.validate();
		keys = Keys.of(property);
	}

	/** 액세스 + 리프레시 토큰 한 쌍 — 로그인 성공 때 */
	public static JwtDto.TokenPair issue(String subject, List<String> roles, Map<String, Object> extraClaims) {
		Keys current = keys();
		String refreshTokenId = UUID.randomUUID().toString();
		return JwtDto.TokenPair.builder()
							   .tokenType("Bearer")
							   .accessToken(sign(current, subject, JwtDto.TokenType.ACCESS, roles, extraClaims, current.property.getAccessTokenTtl(),
								   UUID.randomUUID().toString()))
							   .accessTokenExpiresIn(current.property.getAccessTokenTtl().toSeconds())
							   .refreshToken(sign(current, subject, JwtDto.TokenType.REFRESH, roles, null, current.property.getRefreshTokenTtl(),
								   refreshTokenId))
							   .refreshTokenExpiresIn(current.property.getRefreshTokenTtl().toSeconds())
							   .refreshTokenId(refreshTokenId)
							   .build();
	}

	/** 액세스 토큰만 — 서비스 간 호출용 등 */
	public static String issueAccessToken(String subject, List<String> roles, Map<String, Object> extraClaims) {
		Keys current = keys();
		return sign(current, subject, JwtDto.TokenType.ACCESS, roles, extraClaims, current.property.getAccessTokenTtl(), UUID.randomUUID().toString());
	}

	/**
	 * 서명·만료·발급자 검증 — 종류(ACCESS·REFRESH)는 묻지 않는다.
	 * @throws CoreException JWT-EXPIRED / JWT-INVALID
	 */
	public static JwtDto.Claims verify(String token) {
		Keys current = keys();
		if (StringUtils.isBlank(token) || token.length() > MAX_TOKEN_LENGTH) {
			throw new CoreException(JwtError.INVALID);
		}
		try {
			SignedJWT jwt = SignedJWT.parse(token.trim());
			// 헤더 alg 는 서명 확인 전의 공격자 입력 — 로그 위조를 막으려 원문은 남기지 않고 설정 쪽 알고리즘만 남긴다
			if (!current.algorithm.equals(jwt.getHeader().getAlgorithm())) {
				throw new CoreException(JwtError.INVALID).addErrorLogData("expectedAlg", current.algorithm.getName());
			}
			if (!jwt.verify(current.verifier)) {
				throw new CoreException(JwtError.INVALID);
			}
			return toClaims(current, jwt.getJWTClaimsSet());
		} catch (ParseException | JOSEException | IllegalArgumentException e) {
			throw new CoreException(JwtError.INVALID, JwtError.INVALID.message(), e);
		}
	}

	/** API 호출용 — 리프레시 토큰을 넣으면 JWT-TYPE */
	public static JwtDto.Claims verifyAccessToken(String token) {
		return requireType(verify(token), JwtDto.TokenType.ACCESS);
	}

	/** 갱신용 — 확인 후 호출측이 jti 폐기 여부를 보고 {@link #issue} 로 새 쌍을 만든다 */
	public static JwtDto.Claims verifyRefreshToken(String token) {
		return requireType(verify(token), JwtDto.TokenType.REFRESH);
	}

	/** JWKS — 다른 서비스가 RS256 토큰을 검증할 공개키 목록 ({"keys":[...]}). HS256 이면 빈 목록 */
	public static Map<String, Object> jwks() {
		Keys current = keys();
		return current.jwk == null ? Map.of("keys", List.of()) : new JWKSet(current.jwk).toJSONObject(true);
	}

	/** @return 발급까지 되면 true (검증 전용 설정이면 false) */
	public static boolean isIssuable() {
		return keys().signer != null;
	}

	private static String sign(Keys current, String subject, JwtDto.TokenType type, List<String> roles, Map<String, Object> extra,
							   Duration ttl, String tokenId) {
		if (current.signer == null) {
			throw new CoreException(JwtError.NOT_ISSUABLE);
		}
		if (StringUtils.isBlank(subject)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "토큰 subject 가 비어 있습니다.");
		}
		Instant now = Instant.now();
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
			.issuer(current.property.getIssuer())
			.subject(subject)
			.issueTime(Date.from(now))
			.notBeforeTime(Date.from(now))
			.expirationTime(Date.from(now.plus(ttl)))
			.jwtID(tokenId)
			.claim(TOKEN_TYPE_CLAIM, type.name())
			.claim(ROLES_CLAIM, List.copyOf(CollectionUtils.emptyIfNull(roles)));
		if (StringUtils.isNotBlank(current.property.getAudience())) {
			claims.audience(current.property.getAudience());
		}
		if (extra != null) {
			extra.forEach((key, value) -> {
				if (RESERVED.contains(key)) {
					throw new CoreException(CommonErrorCode.BAD_REQUEST, "예약된 claim 은 추가할 수 없습니다: " + key);
				}
				claims.claim(key, value);
			});
		}
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(current.algorithm).type(JOSEObjectType.JWT).keyID(current.property.getKeyId()).build(),
			claims.build());
		try {
			jwt.sign(current.signer);
			return jwt.serialize();
		} catch (JOSEException e) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "토큰 서명에 실패했습니다.", e);
		}
	}

	private static JwtDto.Claims toClaims(Keys current, JWTClaimsSet set) throws ParseException {
		JwtProperty property = current.property;
		if (!property.getIssuer().equals(set.getIssuer())) {
			throw new CoreException(JwtError.INVALID).addErrorLogData("iss", String.valueOf(set.getIssuer()));
		}
		if (StringUtils.isNotBlank(property.getAudience()) && (set.getAudience() == null || !set.getAudience().contains(property.getAudience()))) {
			throw new CoreException(JwtError.INVALID).addErrorLogData("aud", String.valueOf(set.getAudience()));
		}
		Instant now = Instant.now();
		Duration skew = property.getClockSkew();
		if (set.getExpirationTime() == null || set.getExpirationTime().toInstant().plus(skew).isBefore(now)) {
			throw new CoreException(JwtError.EXPIRED);
		}
		if (set.getNotBeforeTime() != null && set.getNotBeforeTime().toInstant().minus(skew).isAfter(now)) {
			throw new CoreException(JwtError.INVALID);
		}
		JwtDto.TokenType type;
		try {
			type = JwtDto.TokenType.valueOf(String.valueOf(set.getStringClaim(TOKEN_TYPE_CLAIM)));
		} catch (IllegalArgumentException e) {
			throw new CoreException(JwtError.INVALID);
		}
		Map<String, Object> extra = new LinkedHashMap<>(set.getClaims());
		RESERVED.forEach(extra::remove);
		return JwtDto.Claims.builder()
							.subject(set.getSubject())
							.tokenType(type)
							.roles(CollectionUtils.emptyIfNull(set.getStringListClaim(ROLES_CLAIM)))
							.tokenId(set.getJWTID())
							.issuedAt(set.getIssueTime() == null ? null : set.getIssueTime().toInstant())
							.expiresAt(set.getExpirationTime().toInstant())
							.extra(Map.copyOf(extra))
							.build();
	}

	private static JwtDto.Claims requireType(JwtDto.Claims claims, JwtDto.TokenType expected) {
		if (claims.getTokenType() != expected) {
			throw new CoreException(JwtError.WRONG_TYPE);
		}
		return claims;
	}

	private static Keys keys() {
		Keys current = keys;
		if (current == null) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "JwtUtils 미초기화 - core.jwt.private-key·public-key·secret 설정 확인");
		}
		return current;
	}

	/** 설정에서 만든 서명·검증 도구 묶음 */
	private record Keys(JwtProperty property, JWSAlgorithm algorithm, JWSSigner signer, JWSVerifier verifier, RSAKey jwk) {

		static Keys of(JwtProperty property) {
			try {
				if (property.getAlgorithm() == JwtAlgorithm.HS256) {
					byte[] secret = property.getSecret().getBytes(StandardCharsets.UTF_8);
					return new Keys(property, JWSAlgorithm.HS256, new MACSigner(secret), new MACVerifier(secret), null);
				}
				PrivateKey privateKey = StringUtils.isBlank(property.getPrivateKey()) ? null : PemUtils.readPrivateKey(property.getPrivateKey());
				RSAPublicKey publicKey = (RSAPublicKey) (StringUtils.isNotBlank(property.getPublicKey())
					? PemUtils.readPublicKey(property.getPublicKey()) : publicKeyOf(privateKey));
				RSAKey jwk = new RSAKey.Builder(publicKey).keyID(property.getKeyId()).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
				return new Keys(property, JWSAlgorithm.RS256, privateKey == null ? null : new RSASSASigner(privateKey), new RSASSAVerifier(publicKey), jwk);
			} catch (JOSEException | ClassCastException | IllegalArgumentException e) {
				throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "core.jwt 키를 읽을 수 없습니다. RS256 은 RSA 키(2048bit 이상)여야 합니다.", e);
			}
		}

		/** 개인키(CRT)에서 공개키를 만든다 — 공개키를 따로 안 넣어도 되게 */
		private static PublicKey publicKeyOf(PrivateKey privateKey) {
			if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
				throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "core.jwt.public-key 를 넣어 주세요 (개인키에서 공개키를 만들 수 없습니다).");
			}
			try {
				return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
			} catch (GeneralSecurityException e) {
				throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "공개키를 만들 수 없습니다.", e);
			}
		}
	}
}
