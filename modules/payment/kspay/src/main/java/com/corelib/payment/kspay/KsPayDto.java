package com.corelib.payment.kspay;

import com.corelib.lang.StringUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** KSPAY 연동 데이터 전송 객체 */
public class KsPayDto {

	// === 승인 요청 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class ApproveRequest {

		/** 결제키 — 결제창이 넘긴 reCommConId. 로그 출력 금지 */
		@NotBlank
		private String payKey;

		/** 기대 주문번호 — 응답 ordno 와 대조. 응답 구분자(`)와 충돌하는 문자는 금지 */
		@NotBlank
		@Pattern(regexp = "[^`~'\"]*", message = "주문번호에 ` ~ ' \" 문자는 쓸 수 없습니다.")
		private String orderNo;

		/** 기대 결제 금액 — 응답 amt 와 대조 */
		@NotNull
		@Positive(message = "결제 금액은 0보다 커야 합니다.")
		@Max(999_999_999)
		private Integer amount;

		/** 결제창 채널 — 승인 경로와 주문번호 byte 상한 결정 */
		@NotNull
		private Channel channel;
	}

	// === 승인 결과 (통합모듈 백틱 응답) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Approval {

		/** 승인 여부 원문 (authyn: O 승인 / X 거절) — isSuccess() 로만 노출 */
		@Getter(AccessLevel.NONE)
		private final String successFlag;

		/** 거래번호 (trno) — 취소 시 tradeKey(TID) */
		private final String tid;

		/** 거래일시 (trddt + trdtm) — 형식 불일치는 null */
		private final LocalDateTime tradedAt;

		/** 결제 금액 (amt) */
		private final Integer amount;

		/** 승인번호 (authno) */
		private final String approvalNo;

		/** 응답 메시지 1 (msg1) */
		private final String message1;

		/** 응답 메시지 2 (msg2) */
		private final String message2;

		/** 주문번호 (ordno) */
		private final String orderNo;

		/** 발급사 코드 (isscd) */
		private final String issuerCode;

		/** 매입사 코드 (aqucd) */
		private final String acquirerCode;

		/** 승인구분 (result) — 첫 글자가 결제수단 */
		private final String approvalType;

		/** 응답코드 (resultcd) */
		private final String resultCode;

		/** 할부 개월 (halbu, 00 = 일시불) */
		private final String installMonth;

		/** 카드번호 (cardno, 마스킹) — 로그 출력 금지 */
		private final String cardNo;

		/** 상점아이디 (storeid) — mid 와 대조 */
		private final String storeId;

		/** @return 승인(authyn O)이면 true. 가상계좌는 계좌 발급일 뿐 입금 전 — 입금 확인 전 주문 완료 금지 */
		public boolean isSuccess() {
			return "O".equals(successFlag);
		}

		/** @return 승인구분 첫 글자로 판별한 결제수단. 모르는 값이면 null */
		public PayMethod getPayMethod() {
			return PayMethod.fromResult(approvalType);
		}
	}

	// === 취소 요청 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class CancelRequest {

		/** 결제수단 — 취소 API 경로 결정 (VACCOUNT 는 미입금 회수) */
		@NotNull
		private PayMethod payMethod;

		/** 원거래 키 유형 */
		@NotNull
		private TradeKeyType tradeKeyType;

		/** 원거래 키 — TID 면 승인 응답 tid, ORDER_NUMB 면 주문번호 */
		@NotBlank
		@Size(max = 50)
		private String tradeKey;

		/** 원거래일자 (orgTradeDate yyyyMMdd) — ORDER_NUMB 취소 시 필수 */
		private LocalDate tradeDt;

		/** 취소 유형 — CARD·ACCOUNT 만 PARTIAL 가능, 비우면 FULL */
		@Builder.Default
		private CancelType cancelType = CancelType.FULL;

		/** 부분취소 금액 (cancelTotalAmount) — PARTIAL 필수 */
		@Positive(message = "취소 금액은 0보다 커야 합니다.")
		@Max(999_999_999)
		private Integer cancelAmount;

		/** 부분취소 회차 (cancelSeq 1~9) — PARTIAL 필수, 호출측이 관리 */
		@Min(1)
		@Max(9)
		private Integer cancelSeq;

		/** 취소 과세금액 (cancelTaxAmount) — CARD 부분취소, 복합과세 상점만 */
		@PositiveOrZero
		@Max(999_999_999)
		private Integer taxAmount;

		/** 취소 면세금액 (cancelTaxFreeAmount) — CARD 부분취소만 */
		@PositiveOrZero
		@Max(999_999_999)
		private Integer taxFreeAmount;

		/** 가맹점 데이터 — 응답 data.payload 로 돌려받음 */
		@Size(max = 2048)
		private String payload;
	}

	// === 취소 응답 (webfep 공통 envelope) ===
	@Getter
	@Setter
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class CancelResponse {

		/** KSNET 요청 고유값 */
		private String aid;

		/** API 처리 코드 — A0200 은 처리 성공일 뿐, 실제 취소 성공은 isSuccess()(data.respCode) 로 판정 */
		private String code;

		/** API 처리 메시지 */
		private String message;

		/** 취소 상세 */
		private CancelData data;

		/** @return code A0200 이고 respCode 가 0 으로만 이뤄졌으면 true (카드 0000 / 계좌이체 000) */
		@JsonIgnore
		public boolean isSuccess() {
			return "A0200".equals(code) && data != null && data.getRespCode() != null && data.getRespCode().matches("0+");
		}
	}

	@Getter
	@Setter
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class CancelData {

		/** 가맹점 데이터 에코 */
		private String payload;

		/** 거래번호 */
		private String tid;

		/** 거래일시 원문 (yyyyMMddHHmmss) */
		private String tradeDateTime;

		/** 취소 금액 원문 — 휴대폰 취소는 빈 문자열로 온다 */
		private String cancelAmount;

		/** 실제 취소 응답코드 — 성공은 0 으로만(카드 0000 / 계좌이체 000) */
		private String respCode;

		/** 응답 메시지 */
		private String respMessage;

		/** 승인번호 */
		private String approvalNumb;

		/** 발급사 타입/명 (카드) */
		private String issuerCardType;

		private String issuerCardName;

		/** 매입사 타입/명 (카드) */
		private String purchaseCardType;

		private String purchaseCardName;

		/** 은행 타입/명, 가상계좌번호 (가상계좌 회수) */
		private String bankType;

		private String bankName;

		private String virtualAccountNumb;

		/** @return 취소 금액 숫자. 빈값·숫자 아님은 null */
		@JsonIgnore
		public Integer getCancelAmountValue() {
			if (StringUtils.isBlank(cancelAmount)) {
				return null;
			}
			try {
				return Integer.valueOf(cancelAmount.trim());
			} catch (NumberFormatException e) {
				return null;
			}
		}
	}

	/** 결제창 채널 — 통합모듈 승인 경로와 주문번호 byte 상한 */
	@Getter
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public enum Channel {
		/** PC 결제창 (KSPayWebV1.4) */
		PC("/store/KSPayWebV1.4/web_host/recv_post.jsp", 50),
		/** 모바일 결제창 (KSPayMobileV1.4) */
		MOBILE("/store/KSPayMobileV1.4/web_host/recv_post.jsp", 30);

		private final String approvalPath;

		/** 주문번호 최대 byte (MS949) — 넘으면 승인 전에 차단 */
		private final int maxOrderNoBytes;
	}

	/** 결제수단 + webfep 취소 경로 */
	@Getter
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public enum PayMethod {
		/** 신용카드 — 부분취소 9회, 결제일 6개월 이내 */
		CARD("/kspay/webfep/api/v1/card/cancel", true),
		/** 계좌이체 — 부분취소 가능 */
		ACCOUNT("/kspay/webfep/api/v1/account/cancel", true),
		/** 휴대폰 — 당월만, 전액취소만 */
		MOBILE("/kspay/webfep/api/v1/mobile/cancel", false),
		/** 가상계좌 — 미입금 회수(recall), 환불 아님 */
		VACCOUNT("/kspay/webfep/api/v1/vaccount/recall", false);

		private final String cancelPath;

		/** 부분취소(cancelType) 지원 여부 */
		private final boolean isPartialCancelable;

		/** @return 승인구분(result) 첫 글자로 판별 — 1/I 카드, 2 계좌이체, 6 가상계좌, M 휴대폰. 그 외 null */
		public static PayMethod fromResult(String result) {
			if (StringUtils.isBlank(result)) {
				return null;
			}
			return switch (result.trim().charAt(0)) {
				case '1', 'I' -> CARD;
				case '2' -> ACCOUNT;
				case '6' -> VACCOUNT;
				case 'M' -> MOBILE;
				default -> null;
			};
		}
	}

	/** 원거래 키 유형 (orgTradeKeyType) */
	public enum TradeKeyType {
		/** 거래번호 */
		TID,
		/** 주문번호 — 원거래일자 필수 */
		ORDER_NUMB
	}

	/** 취소 유형 (cancelType) */
	public enum CancelType {
		FULL,
		PARTIAL
	}
}
