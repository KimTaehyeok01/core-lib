package com.corelib.mail;

import com.corelib.lang.StringUtils;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * spring.mail.host 가 있어야 Boot 가 JavaMailSender 를 만든다 — 그 뒤에 실행되도록 순서를 고정한다.
 * JavaMail 은 timeout 키가 없으면 무한 대기한다 — SMTP 서버가 멈추면 요청 스레드가 묶이므로,
 * JavaMailSenderImpl 이고 {@code mail.smtp(s).connectiontimeout·timeout·writetimeout} 이 없으면 기본값(연결 10s, 읽기·쓰기 30s)을 넣는다.
 * 사용자가 spring.mail.properties 로 넣은 값은 그대로 둔다.
 */
@AutoConfiguration(after = MailSenderAutoConfiguration.class)
@ConditionalOnBean(JavaMailSender.class)
@EnableConfigurationProperties(MailProperty.class)
public class MailInitialize {

	/** 순서 = 기동 안내 표시 순서 (연결/읽기/쓰기) */
	private static final List<String> TIMEOUT_KEYS = List.of("connectiontimeout", "timeout", "writetimeout");
	private static final Map<String, String> DEFAULT_TIMEOUT_MILLIS = Map.of("connectiontimeout", "10000", "timeout", "30000", "writetimeout", "30000");

	private final JavaMailSender mailSender;

	public MailInitialize(JavaMailSender mailSender, MailProperty property) {
		this.mailSender = mailSender;
		if (mailSender instanceof JavaMailSenderImpl impl) {
			Properties properties = impl.getJavaMailProperties();
			String prefix = prefix(impl);
			TIMEOUT_KEYS.forEach(key -> properties.putIfAbsent(prefix + key, DEFAULT_TIMEOUT_MILLIS.get(key)));
		}
		MailUtils.initialize(mailSender, property);
	}

	/**
	 * 기동 안내용 "timeout=10s/30s/30s" (연결/읽기/쓰기).
	 * 실제 발송에 쓰는 Session 값을 읽는다 — JNDI Session 처럼 위 기본값이 닿지 않는 경우도 그대로 보인다. JavaMailSenderImpl 이 아니면 "-".
	 */
	String timeout() {
		if (!(mailSender instanceof JavaMailSenderImpl impl)) {
			return "timeout=-";
		}
		Properties properties = impl.getSession().getProperties();
		String prefix = prefix(impl);
		return TIMEOUT_KEYS.stream().map(key -> seconds(properties.get(prefix + key))).collect(Collectors.joining("/", "timeout=", ""));
	}

	private static String prefix(JavaMailSenderImpl impl) {
		return "mail." + StringUtils.defaultIfBlank(impl.getProtocol(), "smtp") + ".";
	}

	private static String seconds(Object millis) {
		if (millis == null) {
			return "-";
		}
		String value = String.valueOf(millis).trim();
		try {
			long parsed = Long.parseLong(value);
			return parsed % 1000 == 0 ? parsed / 1000 + "s" : parsed + "ms";
		} catch (NumberFormatException e) {
			return value;
		}
	}
}
