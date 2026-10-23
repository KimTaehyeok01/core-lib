package com.corelib.example.jwt;

import com.corelib.crypto.PemUtils;
import com.corelib.crypto.RsaUtils;
import com.corelib.lang.StringUtils;
import com.corelib.web.CoreSecurityCustomizer;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** 예제 앱 진입점 — JWT_PRIVATE_KEY 가 없으면 임시 RSA 키로 띄운다 (실서비스는 키를 반드시 환경변수·KMS 로) */
@SpringBootApplication
public class JwtExampleApplication {

	public static void main(String[] args) {
		if (StringUtils.isBlank(System.getenv("JWT_PRIVATE_KEY")) && StringUtils.isBlank(System.getProperty("core.jwt.private-key"))) {
			LoggerFactory.getLogger(JwtExampleApplication.class).warn("JWT_PRIVATE_KEY 미설정 - 임시 RSA 키로 기동합니다. 재시작하면 이전 토큰은 무효입니다.");
			System.setProperty("core.jwt.private-key", PemUtils.writePrivateKey(RsaUtils.generateKeyPair(2048).getPrivate()));
		}
		SpringApplication.run(JwtExampleApplication.class, args);
	}

	/** 관리자 API 는 ADMIN 만 — jwt 의 roles 가 ROLE_ADMIN 권한이 된다 */
	@Bean
	CoreSecurityCustomizer adminOnly() {
		return http -> http.authorizeHttpRequests(authorize -> authorize.requestMatchers("/api/admin/**").hasRole("ADMIN"));
	}
}
