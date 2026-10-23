package com.corelib.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class MailUtilsTest {

	/** 실제 SMTP 접속 없이 발송 메시지를 모아두는 sender */
	static class CapturingMailSender extends JavaMailSenderImpl {

		final List<MimeMessage> sent = new ArrayList<>();

		@Override
		protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
			try {
				for (MimeMessage message : mimeMessages) {
					message.saveChanges();    // 실제 발송처럼 Content-Type 등 헤더를 확정
				}
			} catch (MessagingException e) {
				throw new IllegalStateException(e);
			}
			sent.addAll(List.of(mimeMessages));
		}
	}

	private CapturingMailSender sender;

	@BeforeEach
	void setUp() {
		sender = new CapturingMailSender();
		MailUtils.initialize(sender, property("noreply@example.com"));
	}

	private static MailProperty property(String from) {
		MailProperty property = new MailProperty();
		property.setFrom(from);
		return property;
	}

	private static ErrorCode errorCodeOf(Throwable e) {
		return ((CoreException) e).getErrorCode();
	}

	@Test
	@DisplayName("텍스트 메일 - 기본 발신자/수신자/제목/본문 구성")
	void sendText() throws Exception {
		MailUtils.sendText("user@example.com", "가입 안내", "환영합니다");

		MimeMessage message = sender.sent.get(0);
		assertThat(message.getFrom()[0].toString()).isEqualTo("noreply@example.com");
		assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("user@example.com");
		assertThat(message.getSubject()).isEqualTo("가입 안내");
		assertThat(message.getContent()).isEqualTo("환영합니다");
	}

	@Test
	@DisplayName("HTML 메일 - text/html 로 발송")
	void sendHtml() throws Exception {
		MailUtils.sendHtml("user@example.com", "알림", "<b>굵게</b>");

		MimeMessage message = sender.sent.get(0);
		assertThat(message.getContentType()).contains("text/html");
		assertThat(message.getContent()).isEqualTo("<b>굵게</b>");
	}

	@Test
	@DisplayName("첨부/참조 포함 메일 - multipart 와 참조 수신자 구성")
	void sendWithAttachmentAndCc() throws Exception {
		MailDto.SendRequest request = MailDto.SendRequest.builder()
			.from("sender@example.com")
			.to(List.of("a@example.com"))
			.cc(List.of("cc@example.com"))
			.subject("보고서")
			.content("첨부 확인")
			.attachments(List.of(MailDto.Attachment.builder()
				.fileName("report.txt")
				.content("내용".getBytes(StandardCharsets.UTF_8))
				.contentType("text/plain")
				.build()))
			.build();

		MailUtils.send(request);

		MimeMessage message = sender.sent.get(0);
		assertThat(message.getFrom()[0].toString()).isEqualTo("sender@example.com");
		assertThat(message.getRecipients(Message.RecipientType.CC)[0].toString()).isEqualTo("cc@example.com");
		assertThat(((MimeMultipart) message.getContent()).getCount()).isGreaterThanOrEqualTo(2);
	}

	@Test
	@DisplayName("SMTP 오류 - SEND_FAILED")
	void sendFailed() {
		JavaMailSenderImpl failingSender = new JavaMailSenderImpl() {
			@Override
			protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
				throw new MailSendException("smtp down");
			}
		};
		MailUtils.initialize(failingSender, property("noreply@example.com"));

		assertThatThrownBy(() -> MailUtils.sendText("user@example.com", "제목", "본문"))
			.extracting(MailUtilsTest::errorCodeOf)
			.isEqualTo(MailError.SEND_FAILED);
	}

	@Test
	@DisplayName("발신자 미지정 - BAD_REQUEST, 발송 안 함")
	void missingFrom() {
		MailUtils.initialize(sender, property(null));

		assertThatThrownBy(() -> MailUtils.sendText("user@example.com", "제목", "본문"))
			.extracting(MailUtilsTest::errorCodeOf)
			.isEqualTo(CommonErrorCode.BAD_REQUEST);
		assertThat(sender.sent).isEmpty();
	}

	@Test
	@DisplayName("수신자 주소 형식 오류 - BAD_REQUEST")
	void invalidAddress() {
		assertThatThrownBy(() -> MailUtils.sendText("not-an-email", "제목", "본문"))
			.extracting(MailUtilsTest::errorCodeOf)
			.isEqualTo(CommonErrorCode.BAD_REQUEST);
		assertThat(sender.sent).isEmpty();
	}

	@Test
	@DisplayName("제목 누락 - BAD_REQUEST")
	void blankSubject() {
		assertThatThrownBy(() -> MailUtils.sendText("user@example.com", " ", "본문"))
			.extracting(MailUtilsTest::errorCodeOf)
			.isEqualTo(CommonErrorCode.BAD_REQUEST);
	}
}
