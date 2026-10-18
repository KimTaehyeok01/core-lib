// 이메일 인증 예제를 실제 앱으로 띄워 코드 발송(메일 내용) → 확인, 재발송 대기·오입력 폐기 검증 (발송을 가로채는 JavaMailSender)
package com.corelib.example.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class EmailVerificationExampleTest {

	private static final Pattern CODE = Pattern.compile(">\\s*([0-9]{6})\\s*<");

	/** 실제 SMTP 대신 보낸 메일을 모아두는 sender — 앱의 JavaMailSender 를 대체한다 */
	static class CapturingMailSender extends JavaMailSenderImpl {

		final List<MimeMessage> sent = new CopyOnWriteArrayList<>();

		@Override
		protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
			try {
				for (MimeMessage message : mimeMessages) {
					message.saveChanges();
					sent.add(message);
				}
			} catch (MessagingException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	@TestConfiguration
	static class MailTestConfig {

		@Bean
		JavaMailSender javaMailSender() {
			return new CapturingMailSender();
		}
	}

	private final MockMvc mockMvc;
	private final JavaMailSender javaMailSender;

	private CapturingMailSender sender() {
		return (CapturingMailSender) javaMailSender;
	}

	@BeforeEach
	void clear() {
		sender().sent.clear();
	}

	private ResultActions postJson(String path, String json) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	/** 마지막으로 보낸 메일 본문에서 인증코드를 꺼낸다 */
	private String lastCode() throws Exception {
		MimeMessage message = sender().sent.get(sender().sent.size() - 1);
		Matcher matcher = CODE.matcher((String) message.getContent());
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	@Test
	@DisplayName("발송 → 메일(core.mail.from, HTML)에 6자리 코드 → 맞게 입력하면 인증, 같은 코드 재사용 불가")
	void sendAndConfirm() throws Exception {
		postJson("/api/email-verifications/send", "{\"email\":\"user@example.com\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.maskedEmail").value("us**@example.com"));
		MimeMessage message = sender().sent.get(0);
		assertThat(message.getFrom()[0].toString()).isEqualTo("noreply@example.com");
		assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("user@example.com");
		assertThat(message.getContentType()).contains("text/html");
		String code = lastCode();

		postJson("/api/email-verifications/confirm", "{\"email\":\"USER@example.com\",\"code\":\"" + code + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.verifiedAt").exists());
		postJson("/api/email-verifications/confirm", "{\"email\":\"user@example.com\",\"code\":\"" + code + "\"}")
			.andExpect(jsonPath("$.code").value("VERIFY_NOT_REQUESTED"));
	}

	@Test
	@DisplayName("재발송 대기 중 다시 요청 - 429, 메일 안 보냄")
	void resendTooSoon() throws Exception {
		postJson("/api/email-verifications/send", "{\"email\":\"cool@example.com\"}").andExpect(status().isOk());
		postJson("/api/email-verifications/send", "{\"email\":\"cool@example.com\"}")
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("VERIFY_RESEND_TOO_SOON"));
		assertThat(sender().sent).hasSize(1);
	}

	@Test
	@DisplayName("코드를 5번 틀리면 폐기 - 이후 맞는 코드도 거부")
	void tooManyFails() throws Exception {
		postJson("/api/email-verifications/send", "{\"email\":\"brute@example.com\"}").andExpect(status().isOk());
		String code = lastCode();
		String wrong = code.equals("000000") ? "111111" : "000000";

		for (int i = 1; i <= 4; i++) {
			postJson("/api/email-verifications/confirm", "{\"email\":\"brute@example.com\",\"code\":\"" + wrong + "\"}")
				.andExpect(jsonPath("$.code").value("VERIFY_MISMATCH"));
		}
		postJson("/api/email-verifications/confirm", "{\"email\":\"brute@example.com\",\"code\":\"" + wrong + "\"}")
			.andExpect(jsonPath("$.code").value("VERIFY_TOO_MANY_FAILS"));
		postJson("/api/email-verifications/confirm", "{\"email\":\"brute@example.com\",\"code\":\"" + code + "\"}")
			.andExpect(jsonPath("$.code").value("VERIFY_NOT_REQUESTED"));
	}

	@Test
	@DisplayName("이메일 형식 오류 - C400, 발송 안 함")
	void invalidEmail() throws Exception {
		postJson("/api/email-verifications/send", "{\"email\":\"not-email\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("C400"));
		assertThat(sender().sent).isEmpty();
	}
}
