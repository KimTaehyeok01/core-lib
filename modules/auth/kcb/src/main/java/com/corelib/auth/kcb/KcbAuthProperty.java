package com.corelib.auth.kcb;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import java.nio.file.Files;
import java.nio.file.Path;
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

	/** 회원사코드 (12자리) */
	private String cpCd;

	/** 라이선스 파일(.dat) 경로 — 로그 출력 금지 */
	private String licensePath;

	/** 운영/테스트 대상 */
	private KcbAuthDto.Target target = KcbAuthDto.Target.PROD;

	/** 기본 return_url */
	private String returnUrl;

	/** 팝업에 보이는 사이트명 (최대 24 byte) */
	private String siteName;

	/** 사이트 URL */
	private String siteUrl;

	/** 기본 요청 사유 */
	private KcbAuthDto.RequestCause requestCause = KcbAuthDto.RequestCause.SIGN_UP;

	/** 팝업 form 을 POST 할 URL — 테스트 서버 주소는 계약 가이드를 따른다 */
	private String popupUrl = DEFAULT_POPUP_URL;

	/**
	 * 필수 설정과 라이선스 파일 존재를 확인한다.
	 *
	 * @throws CoreException cp-cd / license-path / return-url / site-name / site-url 누락 (500) / LICENSE_NOT_FOUND
	 */
	public void validate() {
		if (StringUtils.isBlank(this.cpCd) || StringUtils.isBlank(this.licensePath) || StringUtils.isBlank(this.returnUrl)
				|| StringUtils.isBlank(this.siteName) || StringUtils.isBlank(this.siteUrl)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
				"auth.kcb.cp-cd / license-path / return-url / site-name / site-url 설정이 없습니다.");
		}
		if (!Files.isReadable(Path.of(this.licensePath))) {
			// 경로 자체는 서버 구조가 드러나 메시지에 넣지 않는다.
			throw new CoreException(KcbAuthError.LICENSE_NOT_FOUND, "auth.kcb.license-path 의 라이선스 파일을 읽을 수 없습니다.");
		}
	}
}
