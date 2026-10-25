package com.corelib.mail;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.lang.CollectionUtils;
import com.corelib.lang.StringUtils;
import com.corelib.common.util.ValidateUtils;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * 메일 발송 진입점 — SMTP 접속은 Spring Boot 표준 {@code spring.mail.*} 가 만든 JavaMailSender 를 쓴다.
 * <ul>
 *   <li>수신자 주소는 개인정보라 예외 메시지·로그에 남기지 않는다 — 건수만 기록한다</li>
 *   <li>발송은 동기 호출이다 — 대량·비동기 발송(재시도 큐)은 소비 프로젝트 책임이다</li>
 * </ul>
 */
@Slf4j
public final class MailUtils {

	/** sender·기본 발신자를 한 번에 바꾼다 — 발송 중인 스레드가 섞인 조합을 보지 않게 */
	private static volatile Config config;

	private MailUtils() {
	}

	/** 기동 시 {@link MailInitialize} 가 1회 호출한다 — 소비자가 직접 부르지 않는다 */
	static void initialize(JavaMailSender initializeMailSender, MailProperty initializeMailProperty) {
		config = new Config(initializeMailSender, initializeMailProperty.getFrom());
	}

	/** 수신자 1명에게 일반 텍스트 메일을 보낸다 */
	public static void sendText(String to, String subject, String text) {
		send(MailDto.SendRequest.text(to, subject, text));
	}

	/** 수신자 1명에게 HTML 메일을 보낸다 */
	public static void sendHtml(String to, String subject, String html) {
		send(MailDto.SendRequest.html(to, subject, html));
	}

	/**
	 * 메일을 보낸다 (참조/숨은참조/첨부 포함). cc·bcc·attachments 가 null 이면 없는 것으로 본다.
	 * @throws CoreException BAD_REQUEST(필수값·주소 형식, 발신자 미지정) / SEND_FAILED(SMTP 오류)
	 */
	public static void send(MailDto.SendRequest request) {
		Config current = config();
		ValidateUtils.validate(request);
		List<String> cc = CollectionUtils.emptyIfNull(request.getCc());
		List<String> bcc = CollectionUtils.emptyIfNull(request.getBcc());
		List<MailDto.Attachment> attachments = CollectionUtils.emptyIfNull(request.getAttachments());

		final String from = StringUtils.defaultIfBlank(request.getFrom(), current.from());
		if (StringUtils.isBlank(from)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "메일 발신자가 없습니다. core.mail.from 또는 요청 from 을 지정하세요.");
		}

		try {
			MimeMessage message = current.mailSender().createMimeMessage();
			// 첨부가 있을 때만 multipart — 단순 메일은 스팸 판정에 유리한 단일 파트로 보낸다.
			MimeMessageHelper helper = new MimeMessageHelper(message, !attachments.isEmpty(), StandardCharsets.UTF_8.name());
			helper.setFrom(from);
			helper.setTo(request.getTo().toArray(String[]::new));
			if (!cc.isEmpty()) {
				helper.setCc(cc.toArray(String[]::new));
			}
			if (!bcc.isEmpty()) {
				helper.setBcc(bcc.toArray(String[]::new));
			}
			helper.setSubject(request.getSubject());
			helper.setText(request.getContent(), request.getContentType() == MailDto.ContentType.HTML);
			for (MailDto.Attachment attachment : attachments) {
				addAttachment(helper, attachment);
			}
			current.mailSender().send(message);
		} catch (MessagingException | MailException e) {
			throw new CoreException(MailError.SEND_FAILED, "메일 발송에 실패했습니다.", e)
				.addErrorLogData("toCount", request.getTo().size())
				.addErrorLogData("attachmentCount", attachments.size());
		}

		log.info("메일 발송 완료 - 수신 {}건, 참조 {}건, 첨부 {}건", request.getTo().size(), cc.size(), attachments.size());
	}

	private static void addAttachment(MimeMessageHelper helper, MailDto.Attachment attachment) throws MessagingException {
		ByteArrayResource source = new ByteArrayResource(attachment.getContent());
		if (StringUtils.isBlank(attachment.getContentType())) {
			helper.addAttachment(attachment.getFileName(), source);
		} else {
			helper.addAttachment(attachment.getFileName(), source, attachment.getContentType());
		}
	}

	/** 초기화 여부를 확인한다 — spring.mail.host 미설정이면 JavaMailSender 가 없어 여기서 드러난다 */
	private static Config config() {
		Config current = config;
		if (current == null || current.mailSender() == null) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "MailUtils 미초기화 - spring.mail.host 설정 확인");
		}
		return current;
	}

	/** 기동 시 정해지는 발송 설정 — 불변 */
	private record Config(JavaMailSender mailSender, String from) {
	}
}
