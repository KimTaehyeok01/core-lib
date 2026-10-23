package com.corelib.payment.toss;

import com.corelib.lang.StringUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 토스페이먼츠 연동 데이터 전송 객체 */
public class TossPayDto {

	// === 승인 요청 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class ConfirmRequest {

		/** 결제 키 - 결제창 successUrl 로 넘어온 paymentKey */
		@NotBlank
		private String paymentKey;

		/** 기대 주문번호 - 호출측이 보관한 값. 응답 orderId 와 대조 */
		@NotBlank
		@Size(max = 64)
		private String orderId;

		/** 기대 결제 금액 - 호출측이 보관한 값(successUrl 의 amount 아님). 응답 totalAmount 와 대조 */
		@NotNull
		@Positive(message = "결제 금액은 0보다 커야 합니다.")
		private Integer amount;
	}

	// === 취소 요청 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class CancelRequest {

		/** 취소 사유 (cancelReason) */
		@NotBlank
		@Size(max = 200)
		private String cancelReason;

		/** 부분취소 금액 (cancelAmount) - null 이면 전액취소 */
		@Positive(message = "취소 금액은 0보다 커야 합니다.")
		private Integer cancelAmount;
	}

	// === 결제 (승인/취소/조회 공통 응답, 토스 Payment 객체 중 필요한 필드만) ===
	@Getter
	@Setter
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Payment {

		/** 결제 키 - 취소/조회 키 */
		private String paymentKey;

		/** 주문번호 */
		private String orderId;

		/** 결제 상태 원문 - getPaymentStatus() 로 enum 조회 */
		private String status;

		/** 결제수단 (토스 원문, 예: 카드 / 가상계좌 / 간편결제) */
		private String method;

		/** 총 결제 금액 */
		private Integer totalAmount;

		/** 취소 가능 잔액 */
		private Integer balanceAmount;

		/** 결제 요청 시각 (ISO 8601) */
		private String requestedAt;

		/** 승인 시각 (ISO 8601) - 가상계좌 입금 전에는 null */
		private String approvedAt;

		/** 가상계좌 정보 - 가상계좌 결제일 때만 */
		private VirtualAccount virtualAccount;

		/** @return 결제 상태 enum. 모르는 값이면 null */
		@JsonIgnore
		public Status getPaymentStatus() {
			return Status.from(status);
		}

		/** @return 승인 완료(DONE)면 true */
		@JsonIgnore
		public boolean isDone() {
			return getPaymentStatus() == Status.DONE;
		}

		/** @return 가상계좌 발급 후 입금 대기면 true - 입금 확인(웹훅) 전 주문 완료 금지 */
		@JsonIgnore
		public boolean isWaitingForDeposit() {
			return getPaymentStatus() == Status.WAITING_FOR_DEPOSIT;
		}
	}

	// === 가상계좌 ===
	@Getter
	@Setter
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class VirtualAccount {

		/** 가상계좌 번호 */
		private String accountNumber;

		/** 은행 코드 */
		private String bankCode;

		/** 입금자명 - 개인정보, 로그 출력 금지 */
		private String customerName;

		/** 입금 기한 (ISO 8601) */
		private String dueDate;
	}

	// === 오류 응답 (4xx 본문) ===
	@Getter
	@Setter
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class ErrorResponse {

		/** 토스 오류 코드 (예: REJECT_CARD_PAYMENT) */
		private String code;

		/** 사용자에게 보여줄 수 있는 오류 메시지 */
		private String message;
	}

	/** 결제 상태 (Payment.status) */
	@Getter
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public enum Status {
		/** 결제 생성 */
		READY("결제 생성"),
		/** 인증 완료, 승인 전 */
		IN_PROGRESS("승인 전"),
		/** 가상계좌 입금 대기 */
		WAITING_FOR_DEPOSIT("입금 대기"),
		/** 승인 완료 */
		DONE("승인 완료"),
		/** 전액 취소 */
		CANCELED("전액 취소"),
		/** 부분 취소 */
		PARTIAL_CANCELED("부분 취소"),
		/** 승인 실패 */
		ABORTED("승인 실패"),
		/** 유효시간 만료 */
		EXPIRED("만료");

		private final String description;

		/** @return 상태 원문으로 찾은 enum (공백 무시). 모르는 값/빈값은 null */
		public static Status from(String value) {
			if (StringUtils.isBlank(value)) {
				return null;
			}
			for (Status status : values()) {
				if (status.name().equals(value.trim())) {
					return status;
				}
			}
			return null;
		}
	}
}
