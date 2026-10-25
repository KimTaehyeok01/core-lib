package com.corelib.payment.nice;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.UrlUtils;
import com.corelib.lang.StringUtils;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** NICEPAY 설정 — 요청 파라미터는 운영/샌드박스가 같고 키와 호스트만 다르다 */
@Getter
@Setter
@ConfigurationProperties("payment.nice")
public class NicePayProperty {

	public static final String DEFAULT_BASE_URL = "https://api.nicepay.co.kr";
	public static final String SANDBOX_BASE_URL = "https://sandbox-api.nicepay.co.kr";
	/** 매뉴얼 권장 — 연결 5초 */
	public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
	/** 매뉴얼 권장 — 읽기 30초. 초과하면 승인 여부를 알 수 없어 망취소한다 */
	public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
	/** 호출 전체 상한의 여유분 — 읽기 timeout 은 read 마다 다시 걸려 느린 응답이 이어지면 끝나지 않는다 */
	static final Duration CALL_TIMEOUT_MARGIN = Duration.ofSeconds(5);

	/** 클라이언트 키 (운영 R1_/R2_, 샌드박스 S1_/S2_) */
	private String clientKey;

	/** 시크릿 키 — 리터럴 금지, {@code ${NICEPAY_SECRET_KEY}} 로만 주입한다. 서명 검증 키 겸용이라 로그 출력 금지 */
	private String secretKey;

	/** API 호스트 — 샌드박스는 {@link #SANDBOX_BASE_URL} */
	private String baseUrl = DEFAULT_BASE_URL;

	private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

	private Duration readTimeout = DEFAULT_READ_TIMEOUT;

	/**
	 * 필수 설정과 base-url 스킴을 확인한다.
	 *
	 * @throws CoreException client-key / secret-key 누락, base-url 이 https 가 아님 (500, 설정 오류)
	 */
	public void validate() {
		if (StringUtils.isBlank(this.clientKey) || StringUtils.isBlank(this.secretKey)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "payment.nice.client-key / secret-key 설정이 없습니다.");
		}
		// http 오설정이면 Basic 인증 헤더(clientKey:secretKey)가 평문으로 나가므로 TLS 를 강제한다.
		if (StringUtils.isNotBlank(this.baseUrl)
				&& !UrlUtils.isHttpsOrLoopback(this.baseUrl)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "payment.nice.base-url 은 https:// 여야 합니다 (로컬 테스트 서버만 http 허용).");
		}
	}

	/** 비어 있으면 운영 호스트 — 끝의 {@code /} 정리는 WebClientUtils 가 한다 */
	public String resolvedBaseUrl() {
		return StringUtils.isBlank(this.baseUrl) ? DEFAULT_BASE_URL : this.baseUrl.trim();
	}

	/** {@code .block()} 전체 상한 = 연결 + 읽기 + 여유 5초 */
	Duration callTimeout() {
		return this.connectTimeout.plus(this.readTimeout).plus(CALL_TIMEOUT_MARGIN);
	}
}
