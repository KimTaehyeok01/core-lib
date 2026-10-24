package com.corelib.example.payment.nice.dto;

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

/** 나이스페이 결제 예제 데이터 전송 객체 */
public class NicePayExampleDto {

	/** 주문 상태 — 실서비스 주문 테이블의 상태 컬럼 */
	public enum OrderStatus {
		/** 주문 생성, 결제 전 — 인증 실패·위변조 콜백이 와도 이 상태로 남아 다시 결제할 수 있다 */
		READY,
		/** 승인 진행 중 — 콜백 하나가 선점했다. 다른 콜백은 승인·망취소를 호출하지 않는다 */
		PROCESSING,
		/** 결제 완료 */
		PAID,
		/** 가상계좌 발급, 입금 대기 */
		WAITING_FOR_DEPOSIT,
		/** 취소 진행 중 — 취소 요청 하나가 선점했다 */
		CANCELING,
		/** 부분 취소 */
		PARTIAL_CANCELED,
		/** 전액 취소 */
		CANCELED,
		/** 결제 실패 (승인 거절·망취소 완료·불일치 자동취소) */
		FAILED,
		/** 승인 결과불명 — 관리자 확인 필요 */
		UNKNOWN,
		/** 취소 결과불명 — 관리자 확인 필요, 이중 환불 위험이라 재취소를 막는다 */
		CANCEL_UNKNOWN
	}

	// === 주문 생성 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class OrderRequest {

		@NotBlank
		@Size(max = 40)
		private String goodsName;

		@NotNull
		@Positive
		@Max(100_000_000)
		private Integer amount;
	}

	// === 주문 생성 응답 — AUTHNICE.requestPay 에 넣을 값 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class OrderResponse {

		private final String orderId;

		private final String goodsName;

		private final Integer amount;

		/** 결제창 clientId — 브라우저에 노출되는 키 (시크릿 키는 서버에만) */
		private final String clientId;
	}

	// === 주문 상세 ===
	@Getter
	@Builder(toBuilder = true)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class OrderDetail {

		private final String orderId;

		private final String goodsName;

		/** 서버가 정한 결제 금액 — 인증결과 검증·승인에 이 값을 쓴다 */
		private final Integer amount;

		private final OrderStatus status;

		/** NICEPAY 거래번호 — 취소·조회에 쓴다 */
		private final String tid;

		private final String payMethod;

		private final String paidAt;

		/** 취소 가능 잔액 */
		private final Integer balanceAmt;

		/** 취소 요청 회차 — 회차마다 취소 orderId 를 {orderId}-c{회차} 로 새로 만든다 (같은 orderId 재호출 불가) */
		private final int cancelCount;

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
		@Size(max = 100)
		private String reason;

		/** 부분취소 금액 — 비우면 전액취소 */
		@Positive
		private Integer cancelAmt;
	}
}
