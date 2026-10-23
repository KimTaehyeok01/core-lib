package com.corelib.example.payment.kspay.dto;

import com.corelib.payment.kspay.KsPayDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** KSPAY 결제 예제 데이터 전송 객체 */
public class KsPayExampleDto {

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
		/** 전액 취소 (가상계좌는 미입금 회수) */
		CANCELED,
		/** 결제 실패 (거절·결제키 무효·불일치 자동취소) */
		FAILED,
		/** 결과불명 — KSTA 관리자 확인 필요 */
		UNKNOWN
	}

	// === 주문 생성 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class OrderRequest {

		@NotBlank
		@Size(max = 50)
		private String goodsName;

		@NotNull
		@Positive
		@Max(100_000_000)
		private Integer amount;
	}

	// === 주문 생성 응답 — KSPayWeb form 에 넣을 값 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class OrderResponse {

		/** sndOrdernumber */
		private final String orderNo;

		/** sndGoodname */
		private final String goodsName;

		/** sndAmount */
		private final Integer amount;

		/** sndStoreid — 상점아이디는 결제창 form 에 들어가는 공개값 */
		private final String storeId;
	}

	// === 주문 상세 ===
	@Getter
	@Builder(toBuilder = true)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class OrderDetail {

		private final String orderNo;

		private final String goodsName;

		/** 서버가 정한 결제 금액 — 승인에 이 값을 쓴다 (결제창 form 의 sndAmount 는 믿지 않는다) */
		private final Integer amount;

		private final OrderStatus status;

		/** KSPAY 거래번호 — 취소 tradeKey */
		private final String tid;

		/** 승인 결과로 판별한 결제수단 — 취소 API 경로를 정한다 */
		private final KsPayDto.PayMethod payMethod;

		private final String approvalNo;

		private final LocalDateTime tradedAt;

		/** 취소 가능 잔액 */
		private final Integer balanceAmount;

		/** 지금까지 부분취소 횟수 — 다음 cancelSeq 는 +1 (최대 9) */
		private final Integer partialCancelCount;

		/** 실패·결과불명 사유 */
		private final String failMessage;
	}

	// === 취소 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class CancelRequest {

		@NotBlank
		private String orderNo;

		/** 부분취소 금액 — 비우면 전액취소 (휴대폰·가상계좌는 전액만) */
		@Positive
		private Integer cancelAmount;
	}
}
