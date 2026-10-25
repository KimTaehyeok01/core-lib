package com.corelib.mail;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 메일 발송 데이터 전송 객체 */
public class MailDto {

	// === 발송 요청 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class SendRequest {

		/** 발신자 — null 이면 core.mail.from */
		@Email
		private String from;

		/** 수신자 — 개인정보라 로그에는 건수만 남긴다 */
		@NotEmpty(message = "수신자가 없습니다.")
		@Builder.Default
		private List<@NotBlank @Email String> to = List.of();

		/** 참조 */
		@Builder.Default
		private List<@NotBlank @Email String> cc = List.of();

		/** 숨은참조 */
		@Builder.Default
		private List<@NotBlank @Email String> bcc = List.of();

		/** 제목 */
		@NotBlank(message = "메일 제목이 없습니다.")
		private String subject;

		/** 본문 — contentType 에 따라 텍스트/HTML 로 보낸다 */
		@NotNull(message = "메일 본문이 없습니다.")
		private String content;

		/** 본문 형식 */
		@NotNull
		@Builder.Default
		private ContentType contentType = ContentType.TEXT;

		/** 첨부파일 — 목록 안의 null 항목은 400 */
		@Builder.Default
		private List<@NotNull @Valid Attachment> attachments = List.of();

		/** 수신자 1명에게 보내는 일반 텍스트 메일 — to 가 null 이면 빈 목록으로 두어 @NotEmpty 로 400 */
		public static SendRequest text(String to, String subject, String text) {
			return SendRequest.builder().to(to == null ? List.of() : List.of(to)).subject(subject).content(text).build();
		}

		/** 수신자 1명에게 보내는 HTML 메일 — to 가 null 이면 빈 목록으로 두어 @NotEmpty 로 400 */
		public static SendRequest html(String to, String subject, String html) {
			return SendRequest.builder().to(to == null ? List.of() : List.of(to)).subject(subject).content(html).contentType(ContentType.HTML).build();
		}
	}

	// === 첨부파일 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class Attachment {

		/** 수신자에게 보이는 파일명 */
		@NotBlank
		private String fileName;

		/** 파일 내용 */
		@NotNull
		private byte[] content;

		/** MIME 타입 — null 이면 파일명 확장자로 추정 */
		private String contentType;
	}

	/** 본문 형식 */
	public enum ContentType {
		/** 일반 텍스트 */
		TEXT,
		/** HTML */
		HTML
	}
}
