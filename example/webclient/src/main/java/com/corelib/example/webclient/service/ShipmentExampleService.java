package com.corelib.example.webclient.service;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.example.webclient.dto.ShipmentExampleDto;
import com.corelib.lang.CollectionUtils;
import com.corelib.lang.MaskingUtils;
import com.corelib.lang.StringUtils;
import com.corelib.webclient.FailureType;
import com.corelib.webclient.WebClientUtils;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 택배 서비스 — 외부 API 실패를 {@link WebClientUtils#classify} 로 나눠 처리한다.
 * <ul>
 *   <li>접수(POST, 재전송하면 이중접수 위험) — 주문번호별로 PENDING 을 선점한 요청만 보낸다. REJECTED·NOT_SENT 는 실패 확정,
 *       UNKNOWN 과 409 DUPLICATE_ORDER 는 재전송 대신 주문번호로 조회해 확인</li>
 *   <li>조회(GET, 몇 번 불러도 안전) — REJECTED 가 아니면 1회 재시도</li>
 *   <li>택배사 4xx 는 401/403 → 502, 409 → 409, 그 외 → 400 으로 바꾼다. 응답 메시지는 고정 문구, 택배사 원문은 로그에만</li>
 * </ul>
 */
@Slf4j
@Service
public class ShipmentExampleService {

	private static final ErrorCode COURIER_UNAVAILABLE = ErrorCode.of("COURIER_UNAVAILABLE", "택배사 서버에 연결할 수 없습니다.", 502);
	private static final ErrorCode COURIER_UNKNOWN = ErrorCode.of("COURIER_UNKNOWN", "택배 접수 결과를 확인할 수 없습니다. 관리자가 확인합니다.", 502);
	private static final String DUPLICATE_ORDER = "DUPLICATE_ORDER";
	/** 택배사 오류코드를 우리 코드(COURIER_{code})에 붙일 때 허용하는 형식 — 벗어나면 REJECTED */
	private static final Pattern COURIER_CODE = Pattern.compile("[A-Za-z0-9_]{1,40}");
	private static final int LOG_MESSAGE_LENGTH = 200;

	/**
	 * 실서비스는 배송 테이블 — 예제라 메모리에 둔다.
	 * 실서비스는 DB 조건부 UPDATE(WHERE status = 'PENDING') 로 같은 원자성 확보
	 */
	private final Map<String, ShipmentExampleDto.Shipment> shipments = new ConcurrentHashMap<>();
	private final WebClient courierClient;

	/** timeout 없는 WebClient 는 만들지 않는다 — 택배사 장애가 우리 스레드를 붙잡지 않게 */
	public ShipmentExampleService(@Value("${example.courier.base-url}") String baseUrl,
								  @Value("${example.courier.api-key:}") String apiKey,
								  @Value("${example.courier.connect-timeout}") Duration connectTimeout,
								  @Value("${example.courier.read-timeout}") Duration readTimeout) {
		WebClient.Builder builder = WebClientUtils.builder(baseUrl, connectTimeout, readTimeout);
		if (StringUtils.isNotBlank(apiKey)) {
			builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
		}
		this.courierClient = builder.build();
	}

	/**
	 * 택배 접수 — 이미 접수됐거나 접수 중·확인 중이면 다시 보내지 않는다.
	 * 없음·FAILED → PENDING 을 compute 로 선점한 요청만 택배사에 POST 한다 (같은 주문 동시 접수 → POST 1회).
	 */
	public ShipmentExampleDto.ShipmentResponse register(ShipmentExampleDto.RegisterRequest request) {
		ValidateUtils.validate(request);
		ShipmentExampleDto.Shipment pending = ShipmentExampleDto.Shipment.builder()
																		 .orderNo(request.getOrderNo())
																		 .receiverName(request.getReceiverName().trim())
																		 .receiverMobileNo(request.getReceiverMobileNo().replaceAll("[^0-9]", ""))
																		 .address(request.getAddress().trim())
																		 .status(ShipmentExampleDto.ShipmentStatus.PENDING)
																		 .requestedAt(LocalDateTime.now())
																		 .build();
		ShipmentExampleDto.Shipment current = shipments.compute(pending.getOrderNo(), (orderNo, existing) ->
			existing == null || existing.getStatus() == ShipmentExampleDto.ShipmentStatus.FAILED ? pending : existing);
		if (current != pending) {
			return toResponse(current);
		}

		ShipmentExampleDto.CourierShipment registered;
		try {
			registered = WebClientUtils.post(courierClient, "/v1/shipments", null,
				ShipmentExampleDto.CourierRegisterRequest.builder()
														 .orderNo(pending.getOrderNo())
														 .receiverName(pending.getReceiverName())
														 .receiverMobileNo(pending.getReceiverMobileNo())
														 .address(pending.getAddress())
														 .build(),
				ShipmentExampleDto.CourierShipment.class).block();
		} catch (RuntimeException e) {
			FailureType failureType = WebClientUtils.classify(e);
			if (failureType == FailureType.REJECTED && isDuplicateOrder(e)) {
				// 택배사에는 이미 접수돼 있다 (앞선 접수의 응답을 못 받은 경우 등) — 실패가 아니라 조회로 송장번호를 확인한다
				log.info("택배사 중복 접수 응답 - 주문번호로 조회해 확인. orderNo={}", pending.getOrderNo());
				return reconcile(pending, e);
			}
			if (failureType == FailureType.REJECTED) {
				CoreException rejected = courierError(e);
				settle(pending, failed(pending, rejected.getMessage()));
				throw rejected.addErrorLogData("orderNo", pending.getOrderNo());
			}
			if (failureType == FailureType.NOT_SENT) {
				settle(pending, failed(pending, "택배사 연결 실패"));
				throw new CoreException(COURIER_UNAVAILABLE, COURIER_UNAVAILABLE.message(), e).addErrorLogData("orderNo", pending.getOrderNo());
			}
			log.warn("택배 접수 결과불명 - 주문번호로 조회해 확인. orderNo={}", pending.getOrderNo());
			return reconcile(pending, e);
		}
		if (!hasInvoice(registered)) {
			// 200 인데 본문(송장번호)이 비었다 — 접수됐는지 모르므로 결과불명과 같이 조회로 확인한다
			log.warn("택배 접수 응답에 송장번호 없음 - 주문번호로 조회해 확인. orderNo={}", pending.getOrderNo());
			return reconcile(pending, null);
		}
		return settle(pending, registered(pending, registered));
	}

	/** 배송 추적 — GET 이라 실패하면 1회 재시도해도 안전하다 */
	public ShipmentExampleDto.TrackResponse track(String orderNo) {
		ShipmentExampleDto.Shipment shipment = find(orderNo);
		if (shipment.getStatus() != ShipmentExampleDto.ShipmentStatus.REGISTERED) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "택배 접수가 완료되지 않은 주문입니다.");
		}
		ShipmentExampleDto.CourierShipment tracked = callWithRetry(() -> WebClientUtils.get(courierClient,
			"/v1/shipments/track?invoiceNo={invoiceNo}", null, ShipmentExampleDto.CourierShipment.class, shipment.getInvoiceNo()).block());
		if (tracked == null) {
			throw new CoreException(COURIER_UNKNOWN, "택배사 응답이 비어 있어 배송 상태를 확인할 수 없습니다.").addErrorLogData("orderNo", shipment.getOrderNo());
		}
		return ShipmentExampleDto.TrackResponse.builder()
											   .orderNo(shipment.getOrderNo())
											   .invoiceNo(shipment.getInvoiceNo())
											   .deliveryStatus(tracked.getStatus())
											   .events(CollectionUtils.emptyIfNull(tracked.getEvents()))
											   .build();
	}

	public ShipmentExampleDto.ShipmentResponse detail(String orderNo) {
		return toResponse(find(orderNo));
	}

	/**
	 * 접수 결과불명 복구 — 택배사가 주문번호로 중복을 막으므로 조회해 보면 접수 여부를 알 수 있다.
	 * 조회도 실패하거나 응답이 비면 접수됐는지 모르는 상태(CHECK_REQUIRED)로 두고 재접수를 막는다.
	 */
	private ShipmentExampleDto.ShipmentResponse reconcile(ShipmentExampleDto.Shipment pending, Throwable registerError) {
		ShipmentExampleDto.CourierShipment found;
		try {
			found = callWithRetry(() -> WebClientUtils.get(courierClient,
				"/v1/shipments/by-order?orderNo={orderNo}", null, ShipmentExampleDto.CourierShipment.class, pending.getOrderNo()).block());
		} catch (CoreException e) {
			if (courierStatus(e) == 404) {
				// 택배사에 없다 = 접수 안 됨 확정 — 다시 접수해도 된다
				settle(pending, failed(pending, "접수되지 않음 - 다시 시도하세요"));
				throw new CoreException(COURIER_UNAVAILABLE, "택배 접수가 되지 않았습니다. 다시 시도해 주세요.", registerError).addErrorLogData("orderNo", pending.getOrderNo());
			}
			throw checkRequired(pending, registerError);
		}
		if (!hasInvoice(found)) {
			log.warn("주문번호 조회 응답에 송장번호 없음 - 관리자 확인 필요. orderNo={}", pending.getOrderNo());
			throw checkRequired(pending, registerError);
		}
		log.info("택배 접수 확인됨(결과불명 복구). orderNo={}, invoiceNo={}", pending.getOrderNo(), found.getInvoiceNo());
		return settle(pending, registered(pending, found));
	}

	private CoreException checkRequired(ShipmentExampleDto.Shipment pending, Throwable registerError) {
		settle(pending, pending.toBuilder().status(ShipmentExampleDto.ShipmentStatus.CHECK_REQUIRED).failMessage(COURIER_UNKNOWN.message()).build());
		return new CoreException(COURIER_UNKNOWN, COURIER_UNKNOWN.message(), registerError).addErrorLogData("orderNo", pending.getOrderNo());
	}

	/** GET 호출 — 거절(4xx)은 우리 오류로 바꿔 바로, 나머지(미전송·결과불명)는 1회 재시도 후 실패 */
	private ShipmentExampleDto.CourierShipment callWithRetry(Supplier<ShipmentExampleDto.CourierShipment> call) {
		RuntimeException last = null;
		for (int attempt = 1; attempt <= 2; attempt++) {
			try {
				return call.get();
			} catch (RuntimeException e) {
				if (WebClientUtils.classify(e) == FailureType.REJECTED) {
					throw courierError(e);
				}
				last = e;
				log.warn("택배사 조회 실패 - attempt={}, failureType={}", attempt, WebClientUtils.classify(e));
			}
		}
		throw new CoreException(COURIER_UNAVAILABLE, COURIER_UNAVAILABLE.message(), last);
	}

	/**
	 * 택배사 4xx 를 우리 오류로 바꾼다 — 응답 메시지는 고정 문구, 택배사 원문은 로그에만 (길이 제한).
	 * <ul>
	 *   <li>401/403 — 우리 API 키·권한 설정 문제다. 그대로 401 로 내보내면 클라이언트가 자기 인증 문제로 오인한다 → 502</li>
	 *   <li>409 → 409, 그 외 4xx → 400. 오류코드는 {@code COURIER_{code}} (형식이 이상하면 REJECTED)</li>
	 * </ul>
	 * 원인 예외를 담아 둔다 — reconcile 이 택배사 404 를 cause 로 판별한다.
	 */
	private static CoreException courierError(Throwable e) {
		int status = courierStatus(e);
		Optional<ShipmentExampleDto.CourierError> body = courierErrorBody(e);
		String rawCode = body.map(ShipmentExampleDto.CourierError::getCode).orElse(null);
		log.warn("택배사 거절 - status={}, code={}, message={}", status, forLog(rawCode),
			forLog(body.map(ShipmentExampleDto.CourierError::getMessage).orElse(null)));
		if (status == 401 || status == 403) {
			return new CoreException(COURIER_UNAVAILABLE, COURIER_UNAVAILABLE.message(), e);
		}
		String code = "COURIER_" + (rawCode != null && COURIER_CODE.matcher(rawCode).matches() ? rawCode : "REJECTED");
		ErrorCode errorCode = status == 409
			? ErrorCode.of(code, "택배사에서 이미 처리된 요청입니다.", 409)
			: ErrorCode.of(code, "택배사가 요청을 거절했습니다. 입력값을 확인해 주세요.", 400);
		return new CoreException(errorCode, errorCode.message(), e);
	}

	private static boolean isDuplicateOrder(Throwable e) {
		return courierStatus(e) == 409
			&& courierErrorBody(e).map(ShipmentExampleDto.CourierError::getCode).filter(DUPLICATE_ORDER::equals).isPresent();
	}

	/** @return 원인 체인의 택배사 4xx 상태 — 없으면 0 */
	private static int courierStatus(Throwable e) {
		return WebClientUtils.findClientError(e).map(error -> error.getStatusCode().value()).orElse(0);
	}

	private static Optional<ShipmentExampleDto.CourierError> courierErrorBody(Throwable e) {
		return WebClientUtils.findClientError(e).map(error -> {
			try {
				return error.getResponseBodyAs(ShipmentExampleDto.CourierError.class);
			} catch (RuntimeException parseError) {
				return null;
			}
		});
	}

	/** 외부 원문을 로그에 남길 때 — 줄바꿈 제거(로그 위조 방지), 길이 제한 */
	private static String forLog(String value) {
		return value == null ? null : StringUtils.truncate(value.replaceAll("[\\r\\n]", " "), LOG_MESSAGE_LENGTH);
	}

	private static boolean hasInvoice(ShipmentExampleDto.CourierShipment courier) {
		return courier != null && StringUtils.isNotBlank(courier.getInvoiceNo());
	}

	private static ShipmentExampleDto.Shipment registered(ShipmentExampleDto.Shipment pending, ShipmentExampleDto.CourierShipment courier) {
		return pending.toBuilder().status(ShipmentExampleDto.ShipmentStatus.REGISTERED).invoiceNo(courier.getInvoiceNo()).failMessage(null).build();
	}

	private static ShipmentExampleDto.Shipment failed(ShipmentExampleDto.Shipment pending, String failMessage) {
		return pending.toBuilder().status(ShipmentExampleDto.ShipmentStatus.FAILED).failMessage(failMessage).build();
	}

	/**
	 * 내가 선점한 PENDING 일 때만 결과를 저장한다 (replace CAS) — 다른 경로가 이미 확정한 REGISTERED 를 FAILED 로 덮지 않는다.
	 * @return 저장된 최신 상태
	 */
	private ShipmentExampleDto.ShipmentResponse settle(ShipmentExampleDto.Shipment pending, ShipmentExampleDto.Shipment result) {
		if (shipments.replace(pending.getOrderNo(), pending, result)) {
			return toResponse(result);
		}
		log.warn("배송 상태가 이미 바뀌어 결과를 저장하지 않음. orderNo={}, result={}", pending.getOrderNo(), result.getStatus());
		return toResponse(shipments.get(pending.getOrderNo()));
	}

	private static ShipmentExampleDto.ShipmentResponse toResponse(ShipmentExampleDto.Shipment shipment) {
		return ShipmentExampleDto.ShipmentResponse.builder()
												  .orderNo(shipment.getOrderNo())
												  .status(shipment.getStatus())
												  .invoiceNo(shipment.getInvoiceNo())
												  .maskedReceiverName(MaskingUtils.name(shipment.getReceiverName()))
												  .maskedReceiverMobileNo(MaskingUtils.mobile(shipment.getReceiverMobileNo()))
												  .failMessage(shipment.getFailMessage())
												  .build();
	}

	private ShipmentExampleDto.Shipment find(String orderNo) {
		ShipmentExampleDto.Shipment shipment = orderNo == null ? null : shipments.get(orderNo);
		if (shipment == null) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "배송 정보를 찾을 수 없습니다.");
		}
		return shipment;
	}
}
