package com.corelib.example.payment.toss.dto;

import jakarta.validation.constraints.Max;
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

/** 토스 결제 예제 데이터 전송 객체 */
public class TossExampleDto {

	/** 주문 상태 — 실서비스 주문 테이블의 상태 컬럼 */
	public enum OrderStatus {
		/** 주문 생성, 결제 전 — 서명 없는 브라우저 입력(failUrl·금액 불일치)이나 토스 승인 거절로는 이 상태를 벗어나지 않는다 */
		READY,
		/** 승인 진행 중 — 한 요청만 READY 에서 선점해 토스 승인을 호출한다 */
		PROCESSING,
		/** 결제 완료 */
		PAID,
		/** 가상계좌 발급, 입금 대기 */
		WAITING_FOR_DEPOSIT,
		/** 부분 취소 */
		PARTIAL_CANCELED,
		/** 취소 진행 중 — 한 요청만 선점해 토스 취소를 호출한다 */
		CANCELING,
		/** 전액 취소 */
		CANCELED,
		/** 취소 결과불명 — 이미 환불됐을 수 있어 재취소 금지, 관리자 확인 필요 */
		CANCEL_UNKNOWN,
		/** 결제 실패 — 승인 후 주문번호·금액 불일치로 자동취소한 경우 */
		FAILED,
		/** 결과불명 — 관리자 확인 필요 */
		UNKNOWN
	}

	// === 주문 생성 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class OrderRequest {

		@NotBlank
		@Size(max = 100)
		private String orderName;

		@NotNull
		@Positive
		@Max(100_000_000)
		private Integer amount;
	}

	// === 주문 생성 응답 — 결제창 SDK 호출에 필요한 값 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class OrderResponse {

		private final String orderId;

		private final String orderName;

		private final Integer amount;

		/** 결제창용 클라이언트 키 (test_ck_ / live_ck_) — 브라우저에 노출되는 키 */
		private final String clientKey;
	}

	// === 주문 상세 ===
	@Getter
	@Builder(toBuilder = true)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class OrderDetail {

		private final String orderId;

		private final String orderName;

		/** 서버가 정한 결제 금액 — 승인 시 이 값을 토스에 보낸다 */
		private final Integer amount;

		private final OrderStatus status;

		/** 토스 결제 키 — 취소·조회에 쓴다 */
		private final String paymentKey;

		private final String method;

		private final String approvedAt;

		/** 취소 가능 잔액 */
		private final Integer balanceAmount;

		/** 실패·결과불명 사유 */
		private final String failMessage;
	}

	// === 취소 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class CancelRequest {

		@NotBlank
		private String orderId;

		@NotBlank
		@Size(max = 200)
		private String reason;

		/** 부분취소 금액 — 비우면 전액취소 */
		@Positive
		private Integer cancelAmount;
	}
}
