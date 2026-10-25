package com.corelib.payment.nice;

import com.corelib.lang.StringUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 나이스페이먼츠 연동 데이터 전송 객체 — 필드명은 NICEPAY 파라미터명 그대로 */
public class NicePayDto {

	// === 결제창 인증 결과 (returnUrl 로 오는 form POST, @ModelAttribute 로 바인딩) ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class AuthResult {

		/** 인증 결과 코드 (0000 = 인증 성공, 그 외 승인 호출 금지) */
		private String authResultCode;

		/** 인증 결과 메시지 */
		private String authResultMsg;

		/** 거래 키 — 인증 성공 시에만 온다. 승인 API 의 경로값 */
		private String tid;

		/** 클라이언트 키 */
		private String clientId;

		/** 주문번호 */
		private String orderId;

		/** 결제 금액 — 브라우저를 거쳐 온 값이라 기대값과 대조 전에는 믿지 않는다 */
		private Integer amount;

		/** 가맹점 예약 필드 */
		private String mallReserved;

		/** 인증 토큰 — 서명 재료 */
		private String authToken;

		/** 위변조 검증 서명 — hex(sha256(authToken + clientId + amount + secretKey)) */
		private String signature;
	}

	// === 승인 요청 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class ApproveRequest {

		/** 거래 키 — verifyAuthResult 가 돌려준 tid */
		@NotBlank
		private String tid;

		/** 기대 주문번호 — 응답 대조와 망취소에 쓴다(승인 본문에는 안 들어감) */
		@NotBlank
		@Size(max = 64)
		private String orderId;

		/** 기대 결제 금액 — 호출측이 보관한 값 */
		@NotNull
		@Positive(message = "결제 금액은 0보다 커야 합니다.")
		@Max(999_999_999)
		private Integer amount;
	}

	// === 취소 요청 (필드명 = NICEPAY 본문 키) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class CancelRequest {

		/** 취소 사유 */
		@NotBlank
		@Size(max = 100)
		private String reason;

		/** 주문번호 — 부분취소는 같은 orderId 로 재호출 불가(매뉴얼) */
		@NotBlank
		@Size(max = 64)
		private String orderId;

		/** 취소 금액 — null 이면 전액취소 (샌드박스는 부분취소 불가) */
		@Positive(message = "취소 금액은 0보다 커야 합니다.")
		@Max(999_999_999)
		private Integer cancelAmt;

		/** 취소 금액 중 면세 금액 */
		@PositiveOrZero
		@Max(999_999_999)
		private Integer taxFreeAmt;

		/** 환불 계좌번호 — 가상계좌 환불 시 */
		@Size(max = 16)
		private String refundAccount;

		/** 환불 은행 코드 — 가상계좌 환불 시 */
		@Size(max = 3)
		private String refundBankCode;

		/** 환불 예금주 — 개인정보, 로그 출력 금지 */
		@Size(max = 10)
		private String refundHolder;
	}

	// === 결제 (승인/취소/망취소/조회 공통 응답) ===
	@Getter
	@Setter
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Payment {

		/** 결과 코드 (0000 = 성공) */
		private String resultCode;

		/** 결과 메시지 */
		private String resultMsg;

		/** 원거래 키 */
		private String tid;

		/** 취소 거래 키 — 취소 응답에만, 부분취소마다 새로 발급 */
		private String cancelledTid;

		/** 주문번호 */
		private String orderId;

		/** 응답 전문 생성 일시 (ISO 8601) — 응답 서명 재료 */
		private String ediDate;

		/** 응답 서명 — hex(sha256(tid + amount + ediDate + secretKey)), 유효 거래만 온다 */
		private String signature;

		/** 결제 상태 원문 — getPaymentStatus() 로 enum 조회 */
		private String status;

		/** 결제 시각 (ISO 8601, 미결제면 0) */
		private String paidAt;

		/** 취소 시각 (ISO 8601, 미취소면 0) */
		private String cancelledAt;

		/** 결제수단 (card, vbank, naverpay, kakaopay, payco, ssgpay, samsungpay, bank, cellphone) */
		private String payMethod;

		/** 결제 금액 */
		private Integer amount;

		/** 취소 가능 잔액 */
		private Integer balanceAmt;

		/** 상품명 */
		private String goodsName;

		/** 승인 번호 */
		private String approveNo;

		/** 매출전표 URL */
		private String receiptUrl;

		/** 카드 정보 — 카드 결제일 때만 */
		private Card card;

		/** 가상계좌 정보 — 가상계좌 결제일 때만 */
		private Vbank vbank;

		/** @return resultCode 가 0000 이면 true */
		@JsonIgnore
		public boolean isSuccess() {
			return "0000".equals(resultCode);
		}

		/** @return 결제 상태 enum. 모르는 값이면 null */
		@JsonIgnore
		public Status getPaymentStatus() {
			return Status.fromCode(status);
		}

		/** @return 결제 완료(paid)면 true */
		@JsonIgnore
		public boolean isPaid() {
			return getPaymentStatus() == Status.PAID;
		}

		/** @return 가상계좌 발급 후 입금 대기면 true — 입금 확인(웹훅) 전 주문 완료 금지 */
		@JsonIgnore
		public boolean isVbankReady() {
			return getPaymentStatus() == Status.READY && "vbank".equals(payMethod);
		}
	}

	// === 카드 ===
	@Getter
	@Setter
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Card {

		/** 카드사 코드 */
		private String cardCode;

		/** 카드사명 */
		private String cardName;

		/** 카드번호 — 마스킹되어 오지만 로그 출력 금지 */
		private String cardNum;

		/** 할부 개월 (0 = 일시불) — 문서는 String, 샘플은 숫자라 문자열로 받는다 */
		private String cardQuota;

		/** 부분취소 가능 여부 (canPartCancel) */
		@JsonProperty("canPartCancel")
		private Boolean isPartialCancelable;

		/** 매입사 코드 */
		private String acquCardCode;

		/** 매입사명 */
		private String acquCardName;
	}

	// === 가상계좌 ===
	@Getter
	@Setter
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Vbank {

		/** 은행 코드 */
		private String vbankCode;

		/** 은행명 */
		private String vbankName;

		/** 가상계좌 번호 */
		private String vbankNumber;

		/** 입금 기한 */
		private String vbankExpDate;

		/** 예금주 — 개인정보, 로그 출력 금지 */
		private String vbankHolder;
	}

	/** 결제 상태 (Payment.status) */
	@Getter
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public enum Status {
		/** 결제 완료 */
		PAID("paid"),
		/** 준비 (가상계좌 입금 대기) */
		READY("ready"),
		/** 실패 */
		FAILED("failed"),
		/** 전액 취소 */
		CANCELLED("cancelled"),
		/** 부분 취소 */
		PARTIAL_CANCELLED("partialCancelled"),
		/** 만료 */
		EXPIRED("expired");

		/** NICEPAY 상태 원문 */
		private final String code;

		/** @return 상태 원문으로 찾은 enum (공백 무시). 모르는 값/빈값은 null */
		public static Status fromCode(String code) {
			if (StringUtils.isBlank(code)) {
				return null;
			}
			for (Status value : values()) {
				if (value.code.equals(code.trim())) {
					return value;
				}
			}
			return null;
		}
	}
}
