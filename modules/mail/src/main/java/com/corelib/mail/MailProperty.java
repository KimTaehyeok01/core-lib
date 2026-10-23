package com.corelib.mail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 메일 기본값 설정 — SMTP 비밀번호(spring.mail.password)는 리터럴 금지, {@code ${MAIL_PASSWORD}} 로만 주입한다 */
@Getter
@Setter
@ConfigurationProperties("core.mail")
public class MailProperty {

	/** 기본 발신자 주소 — 요청에 from 이 없을 때 사용 */
	private String from;
}
