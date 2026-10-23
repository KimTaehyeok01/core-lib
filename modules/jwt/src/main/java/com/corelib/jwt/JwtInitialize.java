package com.corelib.jwt;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** core.jwt 키(private-key·public-key·secret 중 하나)가 있으면 JwtUtils 를 초기화한다 — 없으면 앱은 뜨고 호출 시 '미초기화' */
@AutoConfiguration
@EnableConfigurationProperties(JwtProperty.class)
@ConditionalOnExpression("!'${core.jwt.private-key:}${core.jwt.public-key:}${core.jwt.secret:}'.isBlank()")
public class JwtInitialize {

	public JwtInitialize(JwtProperty property) {
		JwtUtils.initialize(property);
	}
}
