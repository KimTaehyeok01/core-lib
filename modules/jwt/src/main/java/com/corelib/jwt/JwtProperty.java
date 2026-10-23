package com.corelib.jwt;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * core.jwt.* — 키는 환경변수로만 넣는다.
 * <ul>
 *   <li>RS256(기본): private-key 가 있으면 발급+검증, public-key 만 있으면 검증 전용</li>
 *   <li>HS256: secret 하나로 발급+검증 — 같은 비밀값을 아는 서비스끼리만</li>
 * </ul>
 */
@Getter
@Setter
@ConfigurationProperties("core.jwt")
public class JwtProperty {

	private static final int MIN_SECRET_BYTES = 32;

	private JwtAlgorithm algorithm = JwtAlgorithm.RS256;

	/** RS256 개인키 PEM (PKCS#8 "PRIVATE KEY" 또는 PKCS#1 "RSA PRIVATE KEY") */
	private String privateKey;

	/** RS256 공개키 PEM — 비우면 개인키에서 만든다 */
	private String publicKey;

	/** HS256 비밀값 — UTF-8 32byte 이상 */
	private String secret;

	/** 토큰 헤더 kid — 키를 교체할 때 구분용 */
	private String keyId = "core-1";

	/** iss — 발급자. 검증 때 일치해야 한다 */
	private String issuer = "core-lib";

	/** aud — 비우면 넣지도 검사하지도 않는다 */
	private String audience;

	private Duration accessTokenTtl = Duration.ofMinutes(30);

	private Duration refreshTokenTtl = Duration.ofDays(14);

	/** 서버 간 시계 오차 허용 */
	private Duration clockSkew = Duration.ofSeconds(30);

	/** core.jwt.security.enabled — web 모듈 Security 에 Bearer 필터를 붙일지 */
	private final Security security = new Security();

	/** core.jwt.security.* */
	@Getter
	@Setter
	public static class Security {

		private boolean enabled = true;
	}

	/** 기동 시 설정 조합 검사 */
	void validate() {
		if (StringUtils.isBlank(issuer)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "core.jwt.issuer 가 비어 있습니다.");
		}
		if (algorithm == JwtAlgorithm.HS256) {
			if (StringUtils.isBlank(secret) || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
				throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "core.jwt.secret 은 32byte 이상이어야 합니다 (HS256).");
			}
		} else if (StringUtils.isBlank(privateKey) && StringUtils.isBlank(publicKey)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "core.jwt.private-key 또는 public-key 가 필요합니다 (RS256).");
		}
		if (accessTokenTtl.isNegative() || accessTokenTtl.isZero() || refreshTokenTtl.compareTo(accessTokenTtl) < 0) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "core.jwt 토큰 유효시간 설정이 올바르지 않습니다 (refresh ≥ access > 0).");
		}
	}
}
