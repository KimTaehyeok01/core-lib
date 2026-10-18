// 토스페이먼츠 연동 설정 (payment.toss.*)
package com.corelib.payment.toss;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.UrlUtils;
import com.corelib.lang.StringUtils;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 토스페이먼츠 설정 — 테스트/운영은 호스트가 아니라 시크릿 키(test_sk_ / live_sk_)로 구분된다 */
@Getter
@Setter
@ConfigurationProperties("payment.toss")
public class TossPayProperty {

	public static final String DEFAULT_BASE_URL = "https://api.tosspayments.com";
	public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
	public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

	/** 시크릿 키 — 리터럴 금지, {@code ${TOSS_SECRET_KEY}} 로만 주입한다. 로그 출력 금지 */
	private String secretKey;

	/** API 호스트 — 프록시 경유 시 변경용이며 https 만 허용한다 */
	private String baseUrl = DEFAULT_BASE_URL;

	/** 연결 timeout — 연결 실패는 요청 미전송이라 결과확정으로 본다 */
	private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

	/** 읽기 timeout — 초과 시 승인 여부를 알 수 없어 재조회로 확인한다 */
	private Duration readTimeout = DEFAULT_READ_TIMEOUT;

	/**
	 * 필수 설정과 base-url 스킴을 확인한다.
	 *
	 * @throws CoreException secret-key 누락 / base-url 이 https 가 아님 (500, 설정 오류)
	 */
	public void validate() {
		if (StringUtils.isBlank(this.secretKey)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "payment.toss.secret-key 설정이 없습니다.");
		}
		// http 오설정이면 Basic 인증 헤더(시크릿 키)가 평문으로 나가므로 TLS 를 강제한다.
		if (StringUtils.isNotBlank(this.baseUrl)
				&& !UrlUtils.isHttpsOrLoopback(this.baseUrl)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "payment.toss.base-url 은 https:// 여야 합니다 (로컬 테스트 서버만 http 허용).");
		}
	}

	/** 비어 있으면 기본 호스트 — 끝의 {@code /} 정리는 WebClientUtils 가 한다 */
	public String resolvedBaseUrl() {
		return StringUtils.isBlank(this.baseUrl) ? DEFAULT_BASE_URL : this.baseUrl.trim();
	}
}
