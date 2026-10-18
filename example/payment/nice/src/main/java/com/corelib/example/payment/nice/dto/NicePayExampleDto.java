// 나이스페이 결제 예제 요청·응답 데이터
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
		/** 주문 생성, 결제 전 */
		READY,
		/** 결제 완료 */
		PAID,
		/** 가상계좌 발급, 입금 대기 */
		WAITING_FOR_DEPOSIT,
		/** 부분 취소 */
		PARTIAL_CANCELED,
		/** 전액 취소 */
		CANCELED,
		/** 결제 실패 (사용자 취소·거절·위변조·망취소 완료) */
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
