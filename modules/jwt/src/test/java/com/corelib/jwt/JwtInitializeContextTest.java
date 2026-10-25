package com.corelib.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.corelib.common.startup.StartupReport;
import com.corelib.crypto.PemUtils;
import com.corelib.crypto.RsaUtils;
import com.corelib.web.WebInitialize;
import com.corelib.web.WebSecurityInitialize;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;

/** 자동 구성 조건 — 키 없음·있음·잘못됨, 기동 안내의 core 보안 체인 유무 분기 */
class JwtInitializeContextTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(JwtInitialize.class, JwtSecurityInitialize.class, JwtStartupReport.class));

	/** web 모듈 core 보안 체인까지 실제로 만드는 서블릿 웹 앱 */
	private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, WebMvcAutoConfiguration.class, SecurityAutoConfiguration.class,
			WebInitialize.class, WebSecurityInitialize.class, JwtInitialize.class, JwtSecurityInitialize.class, JwtStartupReport.class))
		.withPropertyValues("core.jwt.private-key=" + PemUtils.writePrivateKey(RsaUtils.generateKeyPair(2048).getPrivate()));

	private static StartupReport report(ApplicationContext context) {
		return context.getBean(JwtStartupReport.class).report();
	}

	/** 조건 평가 보고서(--debug 로 찍히는 내용)의 모든 메시지 */
	private static List<String> conditionMessages(AssertableApplicationContext context) {
		List<String> messages = new ArrayList<>();
		ConditionEvaluationReport.get(context.getBeanFactory()).getConditionAndOutcomesBySource().values()
								 .forEach(outcomes -> outcomes.forEach(each -> messages.add(each.getOutcome().getMessage())));
		return messages;
	}

	@Test
	@DisplayName("설정 없음 — 자동설정 꺼짐(앱은 뜸), 기동 안내는 미설정 / 공백 값도 없음으로 본다")
	void noKey() {
		runner.run(context -> {
			assertThat(context).hasNotFailed().doesNotHaveBean(JwtInitialize.class).doesNotHaveBean(JwtSecurityInitialize.CUSTOMIZER_BEAN);
			assertThat(report(context).isReady()).isFalse();
			assertThat(report(context).getDetail()).contains("미설정");
		});
		runner.withPropertyValues("core.jwt.private-key=", "core.jwt.public-key=", "core.jwt.secret=")
			  .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(JwtInitialize.class));
	}

	@Test
	@DisplayName("secret 에 ' 가 있어도 기동 — 값을 표현식에 끼우지 않고, 조건 평가 메시지에는 키 이름만 남는다")
	void secretWithQuote() {
		String secret = "it's-" + UUID.randomUUID() + "'" + UUID.randomUUID();
		runner.withPropertyValues("core.jwt.algorithm=HS256", "core.jwt.secret=" + secret).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(JwtInitialize.class);
			assertThat(JwtUtils.verifyAccessToken(JwtUtils.issueAccessToken("m", List.of(), null)).getSubject()).isEqualTo("m");
			assertThat(conditionMessages(context)).anyMatch(message -> message.contains("core.jwt.secret"))
												  .noneMatch(message -> message.contains(secret));
			assertThat(report(context).getDetail()).contains("HS256 발급+검증").doesNotContain(secret);
		});
	}

	@Test
	@DisplayName("공개키만 — 검증 전용으로 기동")
	void verifyOnly() {
		String publicKey = PemUtils.writePublicKey(RsaUtils.generateKeyPair(2048).getPublic());
		runner.withPropertyValues("core.jwt.public-key=" + publicKey).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(JwtInitialize.class);
			assertThat(report(context).getDetail()).contains("RS256 검증 전용");
		});
	}

	@Test
	@DisplayName("설정 잘못됨 — HS256 짧은 비밀값·읽을 수 없는 RS256 키는 기동 실패")
	void invalid() {
		runner.withPropertyValues("core.jwt.algorithm=HS256", "core.jwt.secret=" + UUID.randomUUID().toString().substring(0, 8))
			  .run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("core.jwt.private-key=" + UUID.randomUUID())
			  .run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("기동 안내 — core 보안 체인이 없으면 customizer 빈이 있어도 'Bearer 필터 없음 (core 보안 체인 미사용)'")
	void reportWithoutCoreChain() {
		runner.withPropertyValues("core.jwt.private-key=" + PemUtils.writePrivateKey(RsaUtils.generateKeyPair(2048).getPrivate())).run(context -> {
			assertThat(context).hasBean(JwtSecurityInitialize.CUSTOMIZER_BEAN).doesNotHaveBean(JwtStartupReport.CORE_CHAIN_BEAN);
			assertThat(report(context).getDetail()).contains("Bearer 필터 없음 (core 보안 체인 미사용)").doesNotContain("Bearer 필터 연동");
		});
		webRunner.withPropertyValues("core.web.security.enabled=false").run(context -> {
			assertThat(context).hasNotFailed().doesNotHaveBean(JwtStartupReport.CORE_CHAIN_BEAN);
			assertThat(report(context).getDetail()).contains("Bearer 필터 없음 (core 보안 체인 미사용)");
		});
	}

	@Test
	@DisplayName("기동 안내 — core 보안 체인 + customizer 면 'Bearer 필터 연동', jwt 보안을 끄면 'Bearer 필터 없음'")
	void reportWithCoreChain() {
		webRunner.run(context -> {
			assertThat(context).hasNotFailed().hasBean(JwtStartupReport.CORE_CHAIN_BEAN).hasBean(JwtSecurityInitialize.CUSTOMIZER_BEAN);
			assertThat(report(context).getDetail()).contains("Bearer 필터 연동");
		});
		webRunner.withPropertyValues("core.jwt.security.enabled=false").run(context -> {
			assertThat(context).hasNotFailed().hasBean(JwtStartupReport.CORE_CHAIN_BEAN).doesNotHaveBean(JwtSecurityInitialize.CUSTOMIZER_BEAN);
			assertThat(report(context).getDetail()).endsWith("Bearer 필터 없음");
		});
	}
}
