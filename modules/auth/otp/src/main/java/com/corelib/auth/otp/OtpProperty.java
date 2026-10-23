package com.corelib.auth.otp;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * auth.otp.* — 기본값이 Google Authenticator 기본값과 같다 (SHA1·6자리·30초).
 * 값을 바꾸면 일부 OTP 앱이 무시하거나 못 읽으니, 바꿀 때는 쓰는 앱에서 확인한다.
 */
@Getter
@Setter
@ConfigurationProperties("auth.otp")
public class OtpProperty {

	/** OTP 앱에 표시되는 서비스 이름 */
	private String issuer = "core-lib";

	private OtpAlgorithm algorithm = OtpAlgorithm.SHA1;

	/** 6 ~ 8 */
	private int digits = 6;

	private Duration period = Duration.ofSeconds(30);

	/** 앞뒤로 허용할 시간 단계 수 — 1 이면 ±30초 (폰 시계 오차) */
	private int window = 1;
}
