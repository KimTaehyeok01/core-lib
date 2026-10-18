// KSPAY 결제 승인(통합모듈)/취소(webfep) 진입점
package com.corelib.payment.kspay;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.lang.StringUtils;
import com.corelib.webclient.FailureType;
import com.corelib.webclient.WebClientUtils;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * KSPAY 결제 연동 진입점 — 무상태, 기대 주문번호/금액은 호출측이 보관한다.
 * <ul>
 *   <li>승인 = 통합모듈 web_host (form POST, MS949 백틱 응답) / 취소 = webfep REST (JSON, pgapi 키)</li>
 *   <li>승인 성공 응답은 상점아이디·주문번호·금액을 대조한다 — 불일치는 승인이 성립한 상태라 취소가 필요하다</li>
 *   <li>승인 결과불명이면 같은 결제키로 1회 재전송해 결과를 다시 받는다 (webfep 에는 거래 조회 API 가 없다)</li>
 *   <li>취소는 결과불명이어도 자동 재시도하지 않는다 — 이중취소 위험</li>
 *   <li>KSPAY 결과코드는 {@code KSPAY_{code}} 로 그대로 넘긴다</li>
 * </ul>
 */
@Slf4j
public final class KsPayUtils {

	/** 승인 응답 요청 필드 (sndRpyParams 순서 = 응답 순서) — 주문번호는 값 길이가 가변이라 맨 끝 */
	private static final List<String> APPROVAL_FIELDS = List.of("authyn", "trno", "trddt", "trdtm", "amt", "authno",
		"msg1", "msg2", "isscd", "aqucd", "result", "resultcd", "halbu", "cardno", "storeid", "ordno");

	/** 통합모듈 응답 문자셋 — EUC-KR 상위집합이라 확장 한글까지 읽는다 */
	private static final Charset MS949 = Charset.isSupported("x-windows-949") ? Charset.forName("x-windows-949") : null;
	private static final DateTimeFormatter TRADED_AT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
	private static final DateTimeFormatter TRADE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
	private static final MediaType FORM_UTF8 = new MediaType(MediaType.APPLICATION_FORM_URLENCODED, StandardCharsets.UTF_8);
	private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

	private static KsPayProperty ksPayProperty;
	private static WebClient approvalClient;
	private static WebClient webfepClient;

	private KsPayUtils() {
	}

	/** 기동 시 {@link KsPayInitialize} 가 1회 호출한다 — 소비자가 직접 부르지 않는다 */
	static void initialize(KsPayProperty initializeKsPayProperty, WebClient initializeApprovalClient,
						   WebClient initializeWebfepClient) {
		ksPayProperty = initializeKsPayProperty;
		approvalClient = initializeApprovalClient;
		webfepClient = initializeWebfepClient;
	}

	/**
	 * 결제를 승인한다.
	 *
	 * @return 검증된 승인 결과 — tid 를 저장해 취소에 쓴다
	 * @throws CoreException BAD_REQUEST(요청 검증, 주문번호 byte 초과) / KSPAY_{resultcd}(승인 거절) /
	 *                       MID_MISMATCH·ORDER_MISMATCH·AMOUNT_MISMATCH(승인 성립, 확인·취소 필요) /
	 *                       EXTERNAL_ERROR(결제키 무효·연결 실패) / RESULT_UNKNOWN(재전송으로도 확인 불가, KSTA 관리자 확인)
	 */
	public static KsPayDto.Approval approve(KsPayDto.ApproveRequest request) {
		KsPayProperty property = property();
		ValidateUtils.validate(request);
		if (MS949 == null) {
			// 승인이 성립한 뒤 응답을 못 읽는 상황을 만들지 않도록 전송 전에 막는다.
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "MS949(x-windows-949) 미지원 런타임 - jdk.charsets 모듈 필요");
		}
		int orderNoBytes = request.getOrderNo().trim().getBytes(MS949).length;
		if (orderNoBytes > request.getChannel().getMaxOrderNoBytes()) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, String.format("주문번호가 너무 깁니다 (%d byte, %s 최대 %d byte)",
				orderNoBytes, request.getChannel(), request.getChannel().getMaxOrderNoBytes()));
		}
		final Map<String, Object> logData = Map.of("orderNo", request.getOrderNo());

		KsPayDto.Approval approval;
		try {
			approval = postApproval(request);
		} catch (RuntimeException first) {
			if (!isUnknown(first)) {
				throw confirmedError(first).addErrorLogData(logData);
			}
			log.warn("KSPAY 승인 결과불명 - 같은 결제키로 1회 재전송. orderNo={}, 사유={}", request.getOrderNo(), first.getClass().getSimpleName());
			approval = resendAfterUnknown(request, first);
		}

		if (!approval.isSuccess()) {
			throw rejected(approval).addErrorLogData(logData);
		}
		verifyApproved(approval, request, property);
		log.info("KSPAY 승인 완료. orderNo={}, tid={}, payMethod={}", request.getOrderNo(), approval.getTid(), approval.getPayMethod());
		return approval;
	}

	/**
	 * 결제를 취소한다 (가상계좌는 미입금 회수). 결과불명이어도 자동 재시도하지 않는다.
	 *
	 * @return 성공한 취소 응답 — data.tid, data.cancelAmount 로 확인한다
	 * @throws CoreException BAD_REQUEST(요청 조합 오류) / INTERNAL_SERVER(api-key 미설정) / KSPAY_{respCode}(취소 거절) /
	 *                       KSPAY_{code}(A0400 파라미터·A0401 인증·A0403 프로토콜 오류) / RESULT_UNKNOWN(KSTA 관리자 확인, 재시도 시 이중취소 위험)
	 */
	public static KsPayDto.CancelResponse cancel(KsPayDto.CancelRequest request) {
		KsPayProperty property = property();
		ValidateUtils.validate(request);
		if (StringUtils.isBlank(property.getApiKey())) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "payment.kspay.api-key 설정이 없습니다. (KSTA > PG상점정보 > pgapi)");
		}
		validateCancel(request);
		final Map<String, Object> logData = Map.of("tradeKey", request.getTradeKey(), "payMethod", request.getPayMethod().name());

		KsPayDto.CancelResponse response;
		try {
			response = WebClientUtils.post(webfepClient, request.getPayMethod().getCancelPath(), h -> h.setContentType(JSON_UTF8),
				buildCancelBody(request, property), KsPayDto.CancelResponse.class).block();
		} catch (RuntimeException e) {
			if (WebClientUtils.classify(e) == FailureType.UNKNOWN) {
				log.error("KSPAY 취소 결과불명 - 재시도 없음, KSTA 관리자 확인 필요. tradeKey={}", request.getTradeKey());
				throw new CoreException(KsPayError.RESULT_UNKNOWN, "결제 취소 결과를 확인할 수 없습니다. 관리자 확인이 필요합니다.", e)
					.addErrorLogData(logData);
			}
			throw new CoreException(KsPayError.EXTERNAL_ERROR, "결제 서버에 연결할 수 없습니다.", e).addErrorLogData(logData);
		}

		if (response == null || StringUtils.isBlank(response.getCode())
				|| "A0500".equals(response.getCode()) || "A0999".equals(response.getCode())) {
			// A0500(서버 오류)·A0999(기타 오류)는 처리 도중 실패일 수 있어 취소 성립 여부를 알 수 없다.
			throw new CoreException(KsPayError.RESULT_UNKNOWN, "결제 취소 결과를 확인할 수 없습니다. 관리자 확인이 필요합니다.")
				.addErrorLogData(logData)
				.addErrorLogData("code", response == null ? null : response.getCode())
				.addErrorLogData("aid", response == null ? null : response.getAid());
		}
		if (!response.isSuccess()) {
			throw cancelRejected(response).addErrorLogData(logData).addErrorLogData("aid", response.getAid());
		}
		log.info("KSPAY 취소 완료. tradeKey={}, tid={}, cancelAmount={}", request.getTradeKey(),
			response.getData().getTid(), response.getData().getCancelAmount());
		return response;
	}

	/** 결과불명 뒤 1회 재전송 — 재전송 응답이 거절이면 첫 요청의 승인 여부를 가릴 수 없어 거절로 확정하지 않는다 */
	private static KsPayDto.Approval resendAfterUnknown(KsPayDto.ApproveRequest request, RuntimeException firstError) {
		KsPayDto.Approval resent;
		try {
			resent = postApproval(request);
		} catch (RuntimeException retryError) {
			log.error("KSPAY 승인 재전송 실패 - KSTA 관리자 확인 필요. orderNo={}", request.getOrderNo());
			CoreException unknown = new CoreException(KsPayError.RESULT_UNKNOWN,
				"결제 결과를 확인할 수 없습니다. 관리자 확인이 필요합니다.", firstError);
			unknown.addSuppressed(retryError);
			throw unknown.addErrorLogData("orderNo", request.getOrderNo());
		}
		if (!resent.isSuccess()) {
			log.error("KSPAY 승인 재전송 응답 거절 - 첫 요청 승인 여부 불명. orderNo={}, resultCode={}", request.getOrderNo(), resent.getResultCode());
			throw new CoreException(KsPayError.RESULT_UNKNOWN, "결제 결과를 확인할 수 없습니다. 관리자 확인이 필요합니다.", firstError)
				.addErrorLogData("orderNo", request.getOrderNo())
				.addErrorLogData("resultCode", resent.getResultCode());
		}
		return resent;
	}

	/** 채널별 승인 경로로 form POST 후 MS949 로 디코딩한 백틱 응답을 해석한다 */
	private static KsPayDto.Approval postApproval(KsPayDto.ApproveRequest request) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("sndCommConId", request.getPayKey());
		form.add("sndActionType", "1");
		form.add("sndAmount", String.valueOf(request.getAmount()));
		form.add("sndRpyParams", String.join("`", APPROVAL_FIELDS));

		byte[] raw = WebClientUtils.post(approvalClient, request.getChannel().getApprovalPath(), h -> h.setContentType(FORM_UTF8),
			form, byte[].class).block();
		if (raw == null || raw.length == 0) {
			throw new IndeterminateResponseException("승인 응답 바디 없음");
		}
		return parseApproval(new String(raw, MS949));
	}

	/**
	 * 백틱 응답을 승인 결과로 바꾼다 — 첫 토큰은 버리고 이후를 APPROVAL_FIELDS 순서로, 요청 외 꼬리 칸은 무시, 빈 값은 null.
	 * 형식이 깨졌으면 결과불명, 모든 값이 비었으면 결제키 무효(결과확정)로 본다.
	 */
	static KsPayDto.Approval parseApproval(String raw) {
		if (StringUtils.isBlank(raw) || raw.indexOf('`') < 0) {
			throw new IndeterminateResponseException("승인 응답 형식 오류(구분자 없음)");
		}
		String[] tokens = raw.split("`", -1);
		if (tokens.length < APPROVAL_FIELDS.size() + 1) {
			throw new IndeterminateResponseException("승인 응답 필드 수 부족(" + (tokens.length - 1) + ")");
		}
		Map<String, String> values = new HashMap<>();
		for (int i = 0; i < APPROVAL_FIELDS.size(); i++) {
			values.put(APPROVAL_FIELDS.get(i), StringUtils.trimToNull(tokens[i + 1]));
		}
		if (values.values().stream().allMatch(Objects::isNull)) {
			throw new CoreException(KsPayError.EXTERNAL_ERROR, "결제 정보가 없거나 만료되었습니다. 다시 결제해 주세요.");
		}
		boolean isApproved = "O".equals(values.get("authyn"));
		if (isApproved && values.get("trno") == null) {
			throw new IndeterminateResponseException("승인 응답에 거래번호(trno) 없음");
		}

		Integer amount;
		try {
			amount = values.get("amt") == null ? null : Integer.valueOf(values.get("amt"));
		} catch (NumberFormatException e) {
			throw new IndeterminateResponseException("승인 응답 금액 해석 실패");
		}
		LocalDateTime tradedAt;
		try {
			tradedAt = values.get("trddt") == null || values.get("trdtm") == null ? null
				: LocalDateTime.parse(values.get("trddt") + values.get("trdtm"), TRADED_AT);
		} catch (DateTimeParseException e) {
			tradedAt = null;
		}

		return KsPayDto.Approval.builder()
								.successFlag(values.get("authyn"))
								.tid(values.get("trno"))
								.tradedAt(tradedAt)
								.amount(amount)
								.approvalNo(values.get("authno"))
								.message1(values.get("msg1"))
								.message2(values.get("msg2"))
								.orderNo(values.get("ordno"))
								.issuerCode(values.get("isscd"))
								.acquirerCode(values.get("aqucd"))
								.approvalType(values.get("result"))
								.resultCode(values.get("resultcd"))
								.installMonth(values.get("halbu"))
								.cardNo(values.get("cardno"))
								.storeId(values.get("storeid"))
								.build();
	}

	/** 승인 응답의 상점아이디 → 주문번호 → 금액을 대조한다 — 다른 상점으로 승인됐으면 우리 api-key 로는 취소도 안 된다 */
	private static void verifyApproved(KsPayDto.Approval approval, KsPayDto.ApproveRequest request, KsPayProperty property) {
		if (!property.getMid().equals(approval.getStoreId())) {
			throw new CoreException(KsPayError.MID_MISMATCH, "다른 상점아이디로 승인된 결제입니다. 관리자 확인이 필요합니다.")
				.addErrorLogData("tid", approval.getTid())
				.addErrorLogData("expectedMid", property.getMid())
				.addErrorLogData("actualMid", StringUtils.truncate(approval.getStoreId(), 64));
		}
		if (!request.getOrderNo().trim().equals(approval.getOrderNo())) {
			throw new CoreException(KsPayError.ORDER_MISMATCH, "결제 주문번호 불일치 - 승인 취소가 필요합니다.")
				.addErrorLogData("tid", approval.getTid())
				.addErrorLogData("expectedOrderNo", request.getOrderNo())
				.addErrorLogData("actualOrderNo", StringUtils.truncate(approval.getOrderNo(), 64));
		}
		if (!request.getAmount().equals(approval.getAmount())) {
			throw new CoreException(KsPayError.AMOUNT_MISMATCH, "결제 금액 불일치 - 승인 취소가 필요합니다.")
				.addErrorLogData("tid", approval.getTid())
				.addErrorLogData("payMethod", approval.getPayMethod())
				.addErrorLogData("expectedAmount", request.getAmount())
				.addErrorLogData("actualAmount", approval.getAmount());
		}
	}

	/** 수단·취소유형·키 조합 검증 — 벤더 파라미터 오류(A0400) 전에 BAD_REQUEST 로 막는다 */
	private static void validateCancel(KsPayDto.CancelRequest request) {
		KsPayDto.PayMethod payMethod = request.getPayMethod();
		KsPayDto.CancelType cancelType = request.getCancelType() == null ? KsPayDto.CancelType.FULL : request.getCancelType();
		boolean hasTax = request.getTaxAmount() != null || request.getTaxFreeAmount() != null;

		if (cancelType == KsPayDto.CancelType.PARTIAL) {
			if (!payMethod.isPartialCancelable()) {
				throw new CoreException(CommonErrorCode.BAD_REQUEST, payMethod + " 결제는 부분취소할 수 없습니다. 전액취소만 가능합니다.");
			}
			if (request.getCancelAmount() == null || request.getCancelSeq() == null) {
				throw new CoreException(CommonErrorCode.BAD_REQUEST, "부분취소는 cancelAmount 와 cancelSeq(1~9)가 필요합니다.");
			}
		} else if (request.getCancelAmount() != null || request.getCancelSeq() != null || hasTax) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "전액취소에는 cancelAmount, cancelSeq, taxAmount, taxFreeAmount 를 지정할 수 없습니다.");
		}
		if (hasTax) {
			if (payMethod != KsPayDto.PayMethod.CARD) {
				throw new CoreException(CommonErrorCode.BAD_REQUEST, "과세/면세 금액은 카드 부분취소에만 지정할 수 있습니다.");
			}
			long taxTotal = (long) Objects.requireNonNullElse(request.getTaxAmount(), 0)
				+ Objects.requireNonNullElse(request.getTaxFreeAmount(), 0);
			if (taxTotal > request.getCancelAmount()) {
				throw new CoreException(CommonErrorCode.BAD_REQUEST, "과세금액 + 면세금액이 취소 금액보다 큽니다.");
			}
		}
		if (request.getTradeKeyType() == KsPayDto.TradeKeyType.ORDER_NUMB && request.getTradeDt() == null) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "주문번호(ORDER_NUMB) 취소는 원거래일자(tradeDt)가 필요합니다.");
		}
	}

	/** 수단별 webfep 요청 본문 — 가이드 예시처럼 모든 값은 문자열, 그 수단이 받는 키는 비어 있어도 "" 로 보낸다 */
	private static Map<String, Object> buildCancelBody(KsPayDto.CancelRequest request, KsPayProperty property) {
		KsPayDto.CancelType cancelType = request.getCancelType() == null ? KsPayDto.CancelType.FULL : request.getCancelType();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("mid", property.getMid());
		body.put("payload", StringUtils.defaultIfBlank(request.getPayload(), ""));
		if (request.getPayMethod().isPartialCancelable()) {
			body.put("cancelType", cancelType.name());
		}
		body.put("orgTradeKeyType", request.getTradeKeyType().name());
		body.put("orgTradeKey", request.getTradeKey());
		body.put("orgTradeDate", request.getTradeDt() == null ? "" : request.getTradeDt().format(TRADE_DATE));
		if (request.getPayMethod().isPartialCancelable()) {
			body.put("cancelTotalAmount", textOf(request.getCancelAmount()));
			body.put("cancelSeq", textOf(request.getCancelSeq()));
		}
		if (request.getPayMethod() == KsPayDto.PayMethod.CARD) {
			body.put("cancelTaxAmount", textOf(request.getTaxAmount()));
			body.put("cancelTaxFreeAmount", textOf(request.getTaxFreeAmount()));
		}
		return body;
	}

	private static String textOf(Integer value) {
		return value == null ? "" : String.valueOf(value);
	}

	/** 결과불명 표지 — 원인 예외와 응답 원문(개인정보 포함 가능)은 담지 않는다 */
	private static boolean isUnknown(RuntimeException error) {
		if (error instanceof IndeterminateResponseException) {
			return true;
		}
		return !(error instanceof CoreException) && WebClientUtils.classify(error) == FailureType.UNKNOWN;
	}

	/** 결과확정 오류 — 결제키 무효(CoreException 그대로), 4xx·연결 실패는 EXTERNAL_ERROR */
	private static CoreException confirmedError(RuntimeException error) {
		if (error instanceof CoreException coreException) {
			return coreException;
		}
		String message = WebClientUtils.classify(error) == FailureType.NOT_SENT ? "결제 서버에 연결할 수 없습니다." : "KSPAY 오류 응답입니다.";
		return new CoreException(KsPayError.EXTERNAL_ERROR, message, error);
	}

	/** 승인 거절 — resultcd 를 KSPAY_{code} 로, 메시지는 msg1/msg2 원문 */
	private static CoreException rejected(KsPayDto.Approval approval) {
		String message = StringUtils.defaultIfBlank(
			String.join(" ", Objects.requireNonNullElse(approval.getMessage1(), ""), Objects.requireNonNullElse(approval.getMessage2(), "")).trim(),
			"결제가 승인되지 않았습니다.");
		String code = StringUtils.defaultIfBlank(approval.getResultCode(), "DECLINED");
		return new CoreException(ErrorCode.of("KSPAY_" + code, message, 400), message);
	}

	/** 취소 거절 — respCode 가 있으면 KSPAY_{respCode}, 없으면 API code. 인증·프로토콜 오류(A0401/A0403)는 설정 문제라 500 */
	private static CoreException cancelRejected(KsPayDto.CancelResponse response) {
		KsPayDto.CancelData data = response.getData();
		boolean hasRespCode = data != null && StringUtils.isNotBlank(data.getRespCode());
		String code = hasRespCode ? data.getRespCode() : response.getCode();
		String message = hasRespCode ? StringUtils.defaultIfBlank(data.getRespMessage(), response.getMessage()) : response.getMessage();
		message = StringUtils.defaultIfBlank(message, "결제 취소에 실패했습니다.");
		int status = "A0401".equals(code) || "A0403".equals(code) ? 500 : 400;
		return new CoreException(ErrorCode.of("KSPAY_" + code, message, status), message);
	}

	/** 초기화 여부를 확인한다 — mid 미설정이면 자동설정이 꺼져 있어 여기서 드러난다 */
	private static KsPayProperty property() {
		if (ksPayProperty == null || approvalClient == null || webfepClient == null) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "KsPayUtils 미초기화 - payment.kspay.mid 설정 확인");
		}
		return ksPayProperty;
	}

	/** 200 이지만 결과를 판정할 수 없는 승인 응답 표지 (원문 미포함) */
	private static final class IndeterminateResponseException extends RuntimeException {

		IndeterminateResponseException(String reason) {
			super(reason, null, false, false);
		}
	}
}
