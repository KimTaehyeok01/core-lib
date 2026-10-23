package com.corelib.payment.kspay;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.UrlUtils;
import com.corelib.lang.StringUtils;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** KSPAY 설정 — 승인(통합모듈)과 취소(webfep)는 호스트가 다르다. api-key 는 취소에만 쓴다 */
@Getter
@Setter
@ConfigurationProperties("payment.kspay")
public class KsPayProperty {

	public static final String DEFAULT_APPROVAL_BASE_URL = "https://kspay.ksnet.to";
	public static final String DEFAULT_WEBFEP_BASE_URL = "https://pay.ksnet.co.kr";
	public static final String DEV_WEBFEP_BASE_URL = "https://paydev.ksnet.co.kr";
	public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
	public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

	/** 상점아이디 — 승인 응답 storeid 와 대조한다 */
	private String mid;

	/** webfep pgapi 키 (KSTA > PG상점정보 > 상점정보) — 리터럴 금지, {@code ${KSPAY_API_KEY}} 로만 주입. 로그 출력 금지 */
	private String apiKey;

	/** 통합모듈 승인 호스트 */
	private String approvalBaseUrl = DEFAULT_APPROVAL_BASE_URL;

	/** webfep 취소 호스트 — 개발 서버는 {@link #DEV_WEBFEP_BASE_URL} */
	private String webfepBaseUrl = DEFAULT_WEBFEP_BASE_URL;

	private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

	private Duration readTimeout = DEFAULT_READ_TIMEOUT;

	/**
	 * 필수 설정과 호스트 스킴을 확인한다 (webfep 는 TLS 1.2 이상 HTTPS 만 허용).
	 *
	 * @throws CoreException mid 누락 / 호스트가 https 아님 (500, 설정 오류)
	 */
	public void validate() {
		if (StringUtils.isBlank(this.mid)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "payment.kspay.mid 설정이 없습니다.");
		}
		requireHttps(this.approvalBaseUrl, "approval-base-url");
		requireHttps(this.webfepBaseUrl, "webfep-base-url");
	}

	private static void requireHttps(String url, String name) {
		if (StringUtils.isNotBlank(url) && !UrlUtils.isHttpsOrLoopback(url)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "payment.kspay." + name + " 은 https:// 여야 합니다 (로컬 테스트 서버만 http 허용).");
		}
	}
}
