// JavaMailSender 빈이 있으면 MailUtils 를 초기화하는 자동설정
package com.corelib.mail;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.mail.javamail.JavaMailSender;

/** spring.mail.host 가 있어야 Boot 가 JavaMailSender 를 만든다 — 그 뒤에 실행되도록 순서를 고정한다 */
@AutoConfiguration(after = MailSenderAutoConfiguration.class)
@ConditionalOnBean(JavaMailSender.class)
@EnableConfigurationProperties(MailProperty.class)
public class MailInitialize {

	public MailInitialize(JavaMailSender mailSender, MailProperty property) {
		MailUtils.initialize(mailSender, property);
	}
}
