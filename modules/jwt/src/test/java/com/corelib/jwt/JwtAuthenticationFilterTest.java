package com.corelib.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.corelib.common.error.ErrorCode;
import com.corelib.crypto.PemUtils;
import com.corelib.crypto.RsaUtils;
import com.corelib.web.WebSecurityInitialize;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** 필터 단독 — 토큰이 잘못돼도 직접 응답하지 않고 사유만 남긴 채 통과시키는지 */
class JwtAuthenticationFilterTest {

	private static final KeyPair KEY_PAIR = RsaUtils.generateKeyPair(2048);

	private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter();
	private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
	private final MockHttpServletResponse response = new MockHttpServletResponse();
	private final AtomicBoolean isPassed = new AtomicBoolean();
	private final AtomicReference<Authentication> seen = new AtomicReference<>();
	private final FilterChain chain = (req, res) -> {
		isPassed.set(true);
		seen.set(SecurityContextHolder.getContext().getAuthentication());
	};

	@BeforeEach
	void setUp() {
		JwtProperty property = new JwtProperty();
		property.setPrivateKey(PemUtils.writePrivateKey(KEY_PAIR.getPrivate()));
		property.setIssuer("shop-api");
		JwtUtils.initialize(property);
		SecurityContextHolder.clearContext();
	}

	private void filterWith(String authorization) throws Exception {
		if (authorization != null) {
			request.addHeader("Authorization", authorization);
		}
		filter.doFilter(request, response, chain);
	}

	private void assertPassedWithoutResponse() {
		assertThat(isPassed).isTrue();
		assertThat(seen.get()).isNull();
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.isCommitted()).isFalse();
		assertThat(response.getContentAsByteArray()).isEmpty();
		assertThat(response.getHeader("WWW-Authenticate")).isNull();
	}

	private ErrorCode authError() {
		return (ErrorCode) request.getAttribute(WebSecurityInitialize.AUTH_ERROR_ATTRIBUTE);
	}

	@Test
	@DisplayName("헤더 없음·Bearer 아님 — 그냥 통과, 사유도 남기지 않는다")
	void noBearer() throws Exception {
		filterWith(null);
		assertPassedWithoutResponse();
		assertThat(authError()).isNull();

		filterWith("Basic dXNlcjpwYXNz");
		assertThat(authError()).isNull();
	}

	@Test
	@DisplayName("유효한 액세스 토큰 — 체인 안에서 로그인 상태(ROLE_ 권한), 끝나면 SecurityContext 를 비운다")
	void authenticated() throws Exception {
		filterWith("bearer " + JwtUtils.issueAccessToken("member-7", List.of("USER", "ROLE_ADMIN"), null));

		assertThat(isPassed).isTrue();
		assertThat(seen.get().getPrincipal()).isInstanceOfSatisfying(JwtDto.Claims.class, claims -> assertThat(claims.getSubject()).isEqualTo("member-7"));
		assertThat(seen.get().getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER", "ROLE_ADMIN");
		assertThat(authError()).isNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	@DisplayName("만료 — 401 을 직접 쓰지 않고 JWT-EXPIRED 를 request attribute 에 남긴 채 인증 없이 통과")
	void expired() throws Exception {
		filterWith("Bearer " + expiredAccessToken());

		assertPassedWithoutResponse();
		assertThat(authError()).isEqualTo(JwtError.EXPIRED);
	}

	@Test
	@DisplayName("위조 → JWT-INVALID, 리프레시 토큰 → JWT-TYPE — 둘 다 응답 없이 통과")
	void invalidAndWrongType() throws Exception {
		filterWith("Bearer forged.token.value");
		assertPassedWithoutResponse();
		assertThat(authError()).isEqualTo(JwtError.INVALID);

		MockHttpServletRequest refreshRequest = new MockHttpServletRequest("GET", "/api/me");
		refreshRequest.addHeader("Authorization", "Bearer " + JwtUtils.issue("member-7", List.of("USER"), null).getRefreshToken());
		filter.doFilter(refreshRequest, response, chain);
		assertThat(refreshRequest.getAttribute(WebSecurityInitialize.AUTH_ERROR_ATTRIBUTE)).isEqualTo(JwtError.WRONG_TYPE);
		assertThat(seen.get()).isNull();
	}

	/** 같은 키로 서명했지만 이미 만료된(시계 오차 30초 초과) 액세스 토큰 */
	private static String expiredAccessToken() throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("shop-api").subject("member-7")
														 .expirationTime(Date.from(Instant.now().minusSeconds(120)))
														 .claim("token_type", "ACCESS").claim("roles", List.of("USER")).build();
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims);
		jwt.sign(new RSASSASigner(KEY_PAIR.getPrivate()));
		return jwt.serialize();
	}
}
