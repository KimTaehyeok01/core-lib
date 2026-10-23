package com.corelib.example.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 고객 문의 예제 데이터 전송 객체 */
public class InquiryExampleDto {

	/** 처리 상태 */
	public enum Status {
		/** 접수됨 */
		RECEIVED,
		/** 답변 완료 */
		ANSWERED
	}

	// === 문의 접수 요청 (공개) ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class CreateRequest {

		@NotBlank
		@Size(max = 30)
		private String name;

		@NotBlank
		@Email
		@Size(max = 100)
		private String email;

		@NotBlank
		@Size(max = 2000)
		private String content;
	}

	// === 접수 결과 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class CreateResponse {

		private final Long inquiryNo;

		/** 문의할 때 알려 달라고 안내하는 추적 번호 — 서버 로그에서 이 요청을 바로 찾는다 */
		private final String traceId;

		private final String receivedAt;
	}

	// === 문의 (저장 모델) — 실서비스는 문의 테이블 ===
	@Getter
	@Builder(toBuilder = true)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Inquiry {

		private final Long inquiryNo;

		private final String name;

		private final String email;

		private final String content;

		/** 접수한 클라이언트 IP — 도배·악용 추적용 */
		private final String clientIp;

		private final String traceId;

		private final Status status;

		private final String answer;

		private final LocalDateTime receivedAt;
	}

	// === 관리자 목록 항목 (마스킹) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class InquirySummary {

		private final Long inquiryNo;

		private final String maskedName;

		private final String maskedEmail;

		private final String content;

		private final String clientIp;

		private final String traceId;

		private final Status status;

		private final String receivedAt;
	}

	// === 답변 요청 (관리자) ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class AnswerRequest {

		@jakarta.validation.constraints.NotNull
		private Long inquiryNo;

		@NotBlank
		@Size(max = 2000)
		private String answer;
	}
}
