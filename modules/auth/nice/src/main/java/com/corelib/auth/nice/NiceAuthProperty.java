package com.corelib.auth.nice;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.UrlUtils;
import com.corelib.lang.StringUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * NICE 통합인증 설정 — client-id/secret 은 NICE 에 서버 출구 IP 를 등록하면 메일로 받는다. 별도 테스트 환경은 없다.
 * client-secret 은 거래번호(txNo) 봉인키 재료 겸용이라 교체하면 발급된 txNo 가 전부 무효가 된다.
 */
@Getter
@Setter
@ConfigurationProperties("auth.nice")
public class NiceAuthProperty {

	public static final String DEFAULT_BASE_URL = "https://auth.niceid.co.kr";
	public static final String DEFAULT_DEV_LANG = "Linux/JAVA";
	/** 거래번호 유효시간의 기본값이자 상한 — NICE transaction_id 가 10분만 유효하다 */
	public static final Duration MAX_TX_TTL = Duration.ofMinutes(10);
	public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
	public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

	private String clientId;

	/** 리터럴 금지 — {@code ${NICE_CLIENT_SECRET}} 로만 주입한다. 로그 출력 금지 */
	private String clientSecret;

	/** 기본 return_url — 요청에 없을 때 사용 (최대 250자) */
	private String returnUrl;

	/** 기본 close_url — 선택 */
	private String closeUrl;

	/** 기본 인증 수단 — 계약된 수단만 지정한다 */
	private List<NiceAuthDto.SvcType> svcTypes = new ArrayList<>(List.of(NiceAuthDto.SvcType.M));

	/** 기본 web_transaction_id 전달 방식 */
	private NiceAuthDto.MethodType methodType = NiceAuthDto.MethodType.GET;

	/** API 호스트 — 프록시 경유 시 변경용이며 https 만 허용한다 */
	private String baseUrl = DEFAULT_BASE_URL;

	/** X-Intc-DevLang 헤더 (OS/언어) */
	private String devLang = DEFAULT_DEV_LANG;

	/** 거래번호(txNo) 유효시간 — 10분 초과 설정은 거부한다 */
	private Duration txTtl = MAX_TX_TTL;

	private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

	private Duration readTimeout = DEFAULT_READ_TIMEOUT;

	/**
	 * 필수 설정과 base-url 스킴, 거래 유효시간을 확인한다.
	 *
	 * @throws CoreException client-id / client-secret / return-url 누락, base-url 이 https 아님, tx-ttl 이 0 이하·10분 초과 (500, 설정 오류)
	 */
	public void validate() {
		if (StringUtils.isBlank(this.clientId) || StringUtils.isBlank(this.clientSecret) || StringUtils.isBlank(this.returnUrl)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "auth.nice.client-id / client-secret / return-url 설정이 없습니다.");
		}
		// http 오설정이면 client_secret(Basic)과 access_token 이 평문으로 나가므로 프록시 경유여도 TLS 를 강제한다.
		if (StringUtils.isNotBlank(this.baseUrl)
				&& !UrlUtils.isHttpsOrLoopback(this.baseUrl)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "auth.nice.base-url 은 https:// 여야 합니다 (로컬 테스트 서버만 http 허용).");
		}
		// NICE 가 10분 뒤 transaction_id 를 만료시키므로, 더 긴 TTL 은 만료 건을 유효로 판정하게 만든다.
		if (this.txTtl == null || this.txTtl.isZero() || this.txTtl.isNegative() || this.txTtl.compareTo(MAX_TX_TTL) > 0) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "auth.nice.tx-ttl 은 0 초과 10분 이하여야 합니다.");
		}
	}

	/** 비어 있으면 기본 호스트 — 끝의 {@code /} 정리는 WebClientUtils 가 한다 */
	public String resolvedBaseUrl() {
		return StringUtils.isBlank(this.baseUrl) ? DEFAULT_BASE_URL : this.baseUrl.trim();
	}
}
