package com.corelib.web;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** core.web.* — 모든 값에 기본값이 있어 아무것도 안 적어도 동작한다 */
@Getter
@Setter
@ConfigurationProperties("core.web")
public class WebProperty {

	private final ClientIp clientIp = new ClientIp();

	private final Trace trace = new Trace();

	private final RequestLog requestLog = new RequestLog();

	private final Security security = new Security();

	/** core.web.client-ip.* */
	@Getter
	@Setter
	public static class ClientIp {

		/**
		 * 이 대역에서 온 요청만 X-Forwarded-For 를 믿는다 (로드밸런서·리버스 프록시 주소).
		 * 기본은 루프백·사설망 — 공인 IP 로 직접 들어온 요청의 헤더는 무시한다
		 */
		private List<String> trustedProxies = new ArrayList<>(List.of(
			"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"));

		/** 클라이언트 IP 를 담는 헤더 — 앞에 있는 것부터 본다 */
		private List<String> headers = new ArrayList<>(List.of("X-Forwarded-For", "X-Real-IP"));
	}

	/** core.web.trace.* */
	@Getter
	@Setter
	public static class Trace {

		/** 요청·응답에 traceId 를 싣는 헤더 — 앞단(게이트웨이)이 준 값이 있으면 그대로 쓴다 */
		private String header = "X-Request-Id";
	}

	/** core.web.request-log.* */
	@Getter
	@Setter
	public static class RequestLog {

		/** false 면 요청 로그를 찍지 않는다 */
		private boolean enabled = true;

		/** 로그에서 뺄 경로 (Ant 패턴) */
		private List<String> excludePaths = new ArrayList<>(List.of(
			"/actuator/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/favicon.ico",
			"/**/*.js", "/**/*.css", "/**/*.png", "/**/*.jpg", "/**/*.svg", "/**/*.ico"));

		/** true 면 JSON 요청·응답 본문도 찍는다 (마스킹·길이 제한 적용) — 운영에서는 끄는 것을 권장 */
		private boolean includeBody = false;

		/** 본문 최대 길이 (넘으면 자른다) */
		private int bodyMaxLength = 2000;

		/** 본문에서 값을 가릴 JSON 키 — 키 이름이 이 단어로 끝나면 가린다 (대소문자 무시, userCi·newPassword 도 해당). name 은 goodsName 같은 키도 가린다 — 개인정보 노출보다 안전한 쪽 */
		private List<String> maskKeys = new ArrayList<>(List.of(
			"password", "secret", "token", "authorization", "cardNo", "cardNumber", "cvc", "ci", "di",
			"mobileNo", "phone", "telNo", "email", "residentNo", "rrn", "accountNo", "birth", "birthDt", "birthday", "birthDate",
			"name", "address"));
	}

	/** core.web.security.* — spring-boot-starter-security 가 있을 때만 쓴다 */
	@Getter
	@Setter
	public static class Security {

		/** false 면 core 기본 보안 체인을 만들지 않는다 */
		private boolean enabled = true;

		/** 아래 공개 경로 밖의 기본 접근 정책 */
		private DefaultAccess defaultAccess = DefaultAccess.AUTHENTICATED;

		/** 인증 없이 열어 둘 경로 (Ant 패턴) — 헬스체크·Swagger·/error 는 기본으로 열린다 */
		private List<String> permitPaths = new ArrayList<>();

		/** CORS 허용 출처 (예: https://admin.my.domain) — 비우면 CORS 를 허용하지 않는다 */
		private List<String> corsAllowedOrigins = new ArrayList<>();

		/** CORS 허용 메서드 */
		private List<String> corsAllowedMethods = new ArrayList<>(List.of("GET", "POST", "OPTIONS"));
	}

	/** 공개 경로 밖의 기본 접근 정책 */
	public enum DefaultAccess {
		/** 인증 필요 (기본) — 열어야 할 경로만 permit-paths 에 적는다 */
		AUTHENTICATED,
		/** 모두 허용 — 막을 경로를 CoreSecurityCustomizer 로 직접 지정할 때 */
		PERMIT_ALL
	}
}
