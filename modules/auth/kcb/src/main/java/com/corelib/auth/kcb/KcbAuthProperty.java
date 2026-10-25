package com.corelib.auth.kcb;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.UrlUtils;
import com.corelib.lang.StringUtils;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KCB 본인확인 설정 — 회원사코드(cp-cd)와 라이선스 파일(.dat)은 KCB 계약 후 받는다.
 * 라이선스 파일은 소스 저장소에 넣지 말고 서버 경로(또는 secret 볼륨)에 둔다.
 */
@Getter
@Setter
@ConfigurationProperties("auth.kcb")
public class KcbAuthProperty {

	public static final String DEFAULT_POPUP_URL = "https://safe.ok-name.co.kr/CommonSvl";
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
	/** return_url 길이 상한 (byte) */
	static final int RETURN_URL_MAX_BYTES = 250;

	/** 회원사코드 (12자리) */
	private String cpCd;

	/** 라이선스 파일(.dat) 경로 — 로그 출력 금지 */
	private String licensePath;

	/** 운영/테스트 대상 */
	private KcbAuthDto.Target target = KcbAuthDto.Target.PROD;

	/** 기본 return_url — http(s) 절대 URL, 250 byte 이하 */
	private String returnUrl;

	/** 팝업에 보이는 사이트명 (최대 24 byte) */
	private String siteName;

	/** 사이트 URL */
	private String siteUrl;

	/** 기본 요청 사유 */
	private KcbAuthDto.RequestCause requestCause = KcbAuthDto.RequestCause.SIGN_UP;

	/** 팝업 form 을 POST 할 URL — https 만 허용한다 (로컬 테스트 서버만 http). 테스트 서버 주소는 계약 가이드를 따른다 */
	private String popupUrl = DEFAULT_POPUP_URL;

	/** OkCert 호출 1회의 대기 상한 — 넘으면 KCB-EXTERNAL. jar 내부 통신 timeout 을 알 수 없어 요청 스레드를 여기서 지킨다 */
	private Duration timeout = DEFAULT_TIMEOUT;

	/**
	 * 필수 설정, URL 형식, timeout, 라이선스 파일 존재를 확인한다. 메시지에는 키 이름만 담는다 (값 없음).
	 *
	 * @throws CoreException cp-cd / license-path / return-url / site-name / site-url 누락, popup-url 이 https 아님,
	 *                       return-url 형식·길이 위반, timeout 0 이하 (500, 설정 오류) / LICENSE_NOT_FOUND
	 */
	public void validate() {
		if (StringUtils.isBlank(this.cpCd) || StringUtils.isBlank(this.licensePath) || StringUtils.isBlank(this.returnUrl)
				|| StringUtils.isBlank(this.siteName) || StringUtils.isBlank(this.siteUrl)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
				"auth.kcb.cp-cd / license-path / return-url / site-name / site-url 설정이 없습니다.");
		}
		// http 오설정이면 브라우저가 회원사코드·모듈 토큰을 평문으로 POST 한다.
		if (!UrlUtils.isHttpsOrLoopback(this.popupUrl)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "auth.kcb.popup-url 은 https:// 여야 합니다 (로컬 테스트 서버만 http 허용).");
		}
		if (!isValidReturnUrl(this.returnUrl)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "auth.kcb.return-url 은 http(s) 절대 URL 이고 250 byte 이하여야 합니다.");
		}
		if (this.timeout == null || this.timeout.isZero() || this.timeout.isNegative()) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "auth.kcb.timeout 은 0 보다 커야 합니다.");
		}
		if (!Files.isReadable(Path.of(this.licensePath))) {
			// 경로 자체는 서버 구조가 드러나 메시지에 넣지 않는다.
			throw new CoreException(KcbAuthError.LICENSE_NOT_FOUND, "auth.kcb.license-path 의 라이선스 파일을 읽을 수 없습니다.");
		}
	}

	/** http(s) 절대 URL(호스트 있음)이고 250 byte 이하인지 — 설정값과 요청값을 같은 기준으로 본다 */
	static boolean isValidReturnUrl(String url) {
		if (StringUtils.isBlank(url)) {
			return false;
		}
		String trimmed = url.trim();
		URI uri;
		try {
			uri = new URI(trimmed);
		} catch (URISyntaxException e) {
			return false;
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		return (scheme.equals("http") || scheme.equals("https")) && StringUtils.isNotBlank(uri.getHost())
			&& trimmed.getBytes(StandardCharsets.UTF_8).length <= RETURN_URL_MAX_BYTES;
	}
}
