package com.corelib.jwt;

import com.corelib.lang.StringUtils;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** core.jwt 키(private-key·public-key·secret 중 하나)가 있으면 JwtUtils 를 초기화한다 — 없으면 앱은 뜨고 호출 시 '미초기화' */
@AutoConfiguration
@EnableConfigurationProperties(JwtProperty.class)
@Conditional(JwtInitialize.OnJwtKeyCondition.class)
public class JwtInitialize {

	public JwtInitialize(JwtProperty property) {
		JwtUtils.initialize(property);
	}

	/**
	 * 키 설정 중 하나라도 비어 있지 않으면 매치 — Environment 로 값을 읽기만 한다.
	 * 값을 SpEL 에 끼우면 ' 하나로 파싱이 깨져 기동이 막히고 오류 메시지에 비밀값이 찍히므로, 표현식·조건 메시지에는 키 이름만 쓴다.
	 */
	static class OnJwtKeyCondition extends SpringBootCondition {

		private static final List<String> KEYS = List.of("core.jwt.private-key", "core.jwt.public-key", "core.jwt.secret");

		@Override
		public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
			ConditionMessage.Builder message = ConditionMessage.forCondition("core.jwt 키");
			for (String key : KEYS) {
				if (StringUtils.isNotBlank(context.getEnvironment().getProperty(key))) {
					return ConditionOutcome.match(message.found("property").items(key));
				}
			}
			return ConditionOutcome.noMatch(message.didNotFind("property", "properties").items(KEYS.toArray()));
		}
	}
}
