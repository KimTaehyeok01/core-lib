package com.corelib.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.corelib.common.startup.StartupReport;
import jakarta.mail.internet.MimeMessage;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class MailInitializeTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class, MailInitialize.class, MailStartupReport.class));

	/** JavaMailSenderImpl 이 아닌 소비 앱 sender — 속성을 알 수 없다 */
	static class CustomMailSender implements JavaMailSender {

		@Override
		public MimeMessage createMimeMessage() {
			throw new UnsupportedOperationException();
		}

		@Override
		public MimeMessage createMimeMessage(InputStream contentStream) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void send(MimeMessage... mimeMessages) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void send(SimpleMailMessage... simpleMessages) {
			throw new UnsupportedOperationException();
		}
	}

	private static Properties javaMailProperties(AssertableApplicationContext context) {
		return context.getBean(JavaMailSenderImpl.class).getJavaMailProperties();
	}

	@Test
	@DisplayName("host 있음·timeout 미설정 - 기본값(연결 10s, 읽기·쓰기 30s) 주입, 기동 안내에 smtp·from·timeout")
	void defaultTimeouts() {
		runner.withPropertyValues("spring.mail.host=localhost", "spring.mail.port=1025", "core.mail.from=noreply@example.com")
			  .run(context -> {
				  Properties properties = javaMailProperties(context);
				  assertThat(properties.getProperty("mail.smtp.connectiontimeout")).isEqualTo("10000");
				  assertThat(properties.getProperty("mail.smtp.timeout")).isEqualTo("30000");
				  assertThat(properties.getProperty("mail.smtp.writetimeout")).isEqualTo("30000");

				  StartupReport report = context.getBean(MailStartupReport.class).report();
				  assertThat(report.isReady()).isTrue();
				  assertThat(report.getDetail()).contains("smtp=localhost:1025", "from=no*****@example.com", "timeout=10s/30s/30s");
			  });
	}

	@Test
	@DisplayName("사용자 timeout 값 유지 - 넣은 키는 그대로, 빠진 키만 기본값")
	void keepsUserTimeouts() {
		runner.withPropertyValues("spring.mail.host=localhost",
				  "spring.mail.properties.mail.smtp.connectiontimeout=3000", "spring.mail.properties.mail.smtp.timeout=5500")
			  .run(context -> {
				  Properties properties = javaMailProperties(context);
				  assertThat(properties.getProperty("mail.smtp.connectiontimeout")).isEqualTo("3000");
				  assertThat(properties.getProperty("mail.smtp.timeout")).isEqualTo("5500");
				  assertThat(properties.getProperty("mail.smtp.writetimeout")).isEqualTo("30000");
				  assertThat(context.getBean(MailStartupReport.class).report().getDetail()).contains("timeout=3s/5500ms/30s");
			  });
	}

	@Test
	@DisplayName("protocol=smtps - mail.smtps.* 키에 기본값, mail.smtp.* 는 건드리지 않음")
	void smtps() {
		runner.withPropertyValues("spring.mail.host=localhost", "spring.mail.protocol=smtps")
			  .run(context -> {
				  Properties properties = javaMailProperties(context);
				  assertThat(properties.getProperty("mail.smtps.connectiontimeout")).isEqualTo("10000");
				  assertThat(properties.getProperty("mail.smtps.timeout")).isEqualTo("30000");
				  assertThat(properties.getProperty("mail.smtps.writetimeout")).isEqualTo("30000");
				  assertThat(properties).doesNotContainKey("mail.smtp.timeout");
			  });
	}

	@Test
	@DisplayName("host 없음 - MailInitialize 꺼짐, 기동 안내는 미초기화와 넣을 설정 이름")
	void notReady() {
		runner.run(context -> {
			assertThat(context).doesNotHaveBean(MailInitialize.class);
			StartupReport report = context.getBean(MailStartupReport.class).report();
			assertThat(report.isReady()).isFalse();
			assertThat(report.getDetail()).contains("spring.mail.host");
		});
	}

	@Test
	@DisplayName("JavaMailSenderImpl 이 아닌 sender - 초기화는 하고 timeout 은 알 수 없어 '-'")
	void customSender() {
		runner.withBean(JavaMailSender.class, CustomMailSender::new)
			  .run(context -> {
				  assertThat(context).hasSingleBean(MailInitialize.class);
				  assertThat(context.getBean(MailStartupReport.class).report().getDetail()).contains("timeout=-");
			  });
	}
}
