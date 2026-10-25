package com.corelib.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.crypto.PemUtils;
import com.corelib.crypto.RsaUtils;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtUtilsTest {

	private static final KeyPair KEY_PAIR = RsaUtils.generateKeyPair(2048);

	private static JwtProperty rs256() {
		JwtProperty property = new JwtProperty();
		property.setPrivateKey(PemUtils.writePrivateKey(KEY_PAIR.getPrivate()));
		property.setIssuer("shop-api");
		return property;
	}

	private static ErrorCode errorOf(Throwable e) {
		return ((CoreException) e).getErrorCode();
	}

	@BeforeEach
	void setUp() {
		JwtUtils.initialize(rs256());
	}

	@Test
	@DisplayName("RS256 발급 → 검증 — subject·roles·추가 claim·jti, 액세스/리프레시 구분")
	void issueAndVerify() {
		JwtDto.TokenPair pair = JwtUtils.issue("member-1", List.of("USER"), Map.of("nickname", "길동"));

		JwtDto.Claims access = JwtUtils.verifyAccessToken(pair.getAccessToken());
		assertThat(access.getSubject()).isEqualTo("member-1");
		assertThat(access.getRoles()).containsExactly("USER");
		assertThat(access.getExtra()).containsEntry("nickname", "길동").doesNotContainKeys("iss", "exp", "roles", "token_type");
		assertThat(access.getExpiresAt()).isAfter(Instant.now().plusSeconds(29 * 60));
		assertThat(pair.getAccessTokenExpiresIn()).isEqualTo(1800);

		JwtDto.Claims refresh = JwtUtils.verifyRefreshToken(pair.getRefreshToken());
		assertThat(refresh.getTokenId()).isEqualTo(pair.getRefreshTokenId());
		assertThat(refresh.getExtra()).doesNotContainKey("nickname");    // 리프레시 토큰에는 추가 정보를 싣지 않는다

		assertThatThrownBy(() -> JwtUtils.verifyAccessToken(pair.getRefreshToken())).extracting(JwtUtilsTest::errorOf).isEqualTo(JwtError.WRONG_TYPE);
		assertThatThrownBy(() -> JwtUtils.verifyRefreshToken(pair.getAccessToken())).extracting(JwtUtilsTest::errorOf).isEqualTo(JwtError.WRONG_TYPE);
	}

	@Test
	@DisplayName("만료 — 시계 오차(30초)를 넘으면 JWT-EXPIRED, 안쪽이면 통과")
	void expired() throws Exception {
		assertThatThrownBy(() -> JwtUtils.verify(signed(Instant.now().minusSeconds(60), "shop-api", JwtAlgorithm.RS256)))
			.extracting(JwtUtilsTest::errorOf).isEqualTo(JwtError.EXPIRED);
		assertThat(JwtUtils.verify(signed(Instant.now().minusSeconds(10), "shop-api", JwtAlgorithm.RS256)).getSubject()).isEqualTo("member-1");
	}

	@Test
	@DisplayName("위조 — 서명 변조·다른 발급자·alg:none·HS256 위장·깨진 형식·빈값은 전부 JWT-INVALID")
	void forged() throws Exception {
		String token = JwtUtils.issueAccessToken("member-1", List.of("USER"), null);
		String[] parts = token.split("\\.");
		String tamperedPayload = Base64URL.encode("{\"sub\":\"admin\",\"iss\":\"shop-api\",\"token_type\":\"ACCESS\",\"exp\":9999999999}").toString();

		assertThatThrownBy(() -> JwtUtils.verify(parts[0] + "." + tamperedPayload + "." + parts[2])).extracting(JwtUtilsTest::errorOf).isEqualTo(JwtError.INVALID);
		assertThatThrownBy(() -> JwtUtils.verify(signed(Instant.now().plusSeconds(600), "other-service", JwtAlgorithm.RS256)))
			.extracting(JwtUtilsTest::errorOf).isEqualTo(JwtError.INVALID);
		String none = Base64URL.encode("{\"alg\":\"none\"}") + "." + parts[1] + ".";
		assertThatThrownBy(() -> JwtUtils.verify(none)).extracting(JwtUtilsTest::errorOf).isEqualTo(JwtError.INVALID);
		// 공개키(누구나 아는 값)를 HMAC 비밀값으로 써서 서명하는 고전적인 바꿔치기 공격
		assertThatThrownBy(() -> JwtUtils.verify(signed(Instant.now().plusSeconds(600), "shop-api", JwtAlgorithm.HS256)))
			.extracting(JwtUtilsTest::errorOf).isEqualTo(JwtError.INVALID);
		assertThatThrownBy(() -> JwtUtils.verify("not.a.jwt")).extracting(JwtUtilsTest::errorOf).isEqualTo(JwtError.INVALID);
		assertThatThrownBy(() -> JwtUtils.verify(" ")).extracting(JwtUtilsTest::errorOf).isEqualTo(JwtError.INVALID);
		assertThatThrownBy(() -> JwtUtils.verify("a".repeat(9000))).extracting(JwtUtilsTest::errorOf).isEqualTo(JwtError.INVALID);
	}

	@Test
	@DisplayName("alg 불일치 — 서명 전 헤더의 alg 원문(개행 포함 위조 값)은 로그 데이터에 싣지 않고 설정 알고리즘만 남긴다")
	void algNotLogged() {
		String payload = JwtUtils.issueAccessToken("member-1", List.of("USER"), null).split("\\.")[1];
		String forgedAlg = "HS512\r\nINFO forged-log-line";
		String token = Base64URL.encode("{\"alg\":\"" + forgedAlg.replace("\r", "\\r").replace("\n", "\\n") + "\"}") + "." + payload + ".c2ln";

		assertThatThrownBy(() -> JwtUtils.verify(token))
			.isInstanceOfSatisfying(CoreException.class, e -> {
				assertThat(e.getErrorCode()).isEqualTo(JwtError.INVALID);
				assertThat(e.getErrorLogData()).doesNotContainKey("alg").containsEntry("expectedAlg", "RS256");
				assertThat(e.getErrorLogData().values()).noneMatch(value -> String.valueOf(value).contains("forged-log-line"));
				assertThat(e.getMessage()).doesNotContain("forged-log-line");
			});
	}

	@Test
	@DisplayName("검증 전용(공개키만) — 다른 서비스 토큰 검증은 되고 발급은 JWT-KEY")
	void verifyOnly() {
		String token = JwtUtils.issueAccessToken("member-1", List.of("USER"), null);
		JwtProperty verifyOnly = new JwtProperty();
		verifyOnly.setPublicKey(PemUtils.writePublicKey(KEY_PAIR.getPublic()));
		verifyOnly.setIssuer("shop-api");
		JwtUtils.initialize(verifyOnly);

		assertThat(JwtUtils.isIssuable()).isFalse();
		assertThat(JwtUtils.verifyAccessToken(token).getSubject()).isEqualTo("member-1");
		assertThatThrownBy(() -> JwtUtils.issueAccessToken("x", List.of(), null)).extracting(JwtUtilsTest::errorOf).isEqualTo(JwtError.NOT_ISSUABLE);
	}

	@Test
	@DisplayName("JWKS — 공개키만(kid·n·e), 개인키 성분(d·p·q) 없음")
	@SuppressWarnings("unchecked")
	void jwks() {
		List<Map<String, Object>> keys = (List<Map<String, Object>>) JwtUtils.jwks().get("keys");

		assertThat(keys).hasSize(1);
		assertThat(keys.get(0)).containsEntry("kid", "core-1").containsEntry("alg", "RS256").containsKeys("n", "e").doesNotContainKeys("d", "p", "q");
	}

	@Test
	@DisplayName("HS256 — 32byte 이상 비밀값으로 왕복, 짧은 비밀값은 기동 오류 / 예약 claim 덮어쓰기 거부")
	void hs256AndReserved() {
		JwtProperty hs = new JwtProperty();
		hs.setAlgorithm(JwtAlgorithm.HS256);
		hs.setSecret("0123456789abcdef0123456789abcdef");
		JwtUtils.initialize(hs);
		assertThat(JwtUtils.verifyAccessToken(JwtUtils.issueAccessToken("m", List.of(), null)).getSubject()).isEqualTo("m");
		assertThat((List<?>) JwtUtils.jwks().get("keys")).isEmpty();

		assertThatThrownBy(() -> JwtUtils.issueAccessToken("m", List.of(), Map.of("sub", "admin"))).isInstanceOf(CoreException.class);
		hs.setSecret("short");
		assertThatThrownBy(() -> JwtUtils.initialize(hs)).isInstanceOf(CoreException.class);
	}

	/** 만료·발급자·알고리즘을 마음대로 정한 토큰 — 공격자 시나리오 재현용 */
	private static String signed(Instant expiresAt, String issuer, JwtAlgorithm algorithm) throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer).subject("member-1").expirationTime(Date.from(expiresAt))
														 .claim("token_type", "ACCESS").claim("roles", List.of("ADMIN")).build();
		if (algorithm == JwtAlgorithm.HS256) {
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
			byte[] publicKeyAsSecret = PemUtils.writePublicKey(KEY_PAIR.getPublic()).getBytes(StandardCharsets.UTF_8);
			jwt.sign(new MACSigner(publicKeyAsSecret));
			return jwt.serialize();
		}
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims);
		jwt.sign(new RSASSASigner(KEY_PAIR.getPrivate()));
		return jwt.serialize();
	}
}
