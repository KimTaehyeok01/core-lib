// WebClientUtils 로 택배사 API 를 호출해 접수·배송 조회를 하는 예제 서비스 (배송은 메모리 보관)
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 택배 서비스 — 외부 API 실패를 {@link WebClientUtils#classify} 로 나눠 처리한다.
 * <ul>
 *   <li>접수(POST, 재전송하면 이중접수 위험) — REJECTED·NOT_SENT 는 실패 확정, UNKNOWN 은 재전송 대신 주문번호로 조회해 확인</li>
 *   <li>조회(GET, 몇 번 불러도 안전) — REJECTED 가 아니면 1회 재시도</li>
 *   <li>택배사 오류코드는 {@code COURIER_{code}} 로 그대로 넘긴다</li>
 * </ul>
 */
@Slf4j
@Service
public class ShipmentExampleService {

	private static final ErrorCode COURIER_UNAVAILABLE = ErrorCode.of("COURIER_UNAVAILABLE", "택배사 서버에 연결할 수 없습니다.", 502);
	private static final ErrorCode COURIER_UNKNOWN = ErrorCode.of("COURIER_UNKNOWN", "택배 접수 결과를 확인할 수 없습니다. 관리자가 확인합니다.", 502);

	/** 실서비스는 배송 테이블 — 예제라 메모리에 둔다 */
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

	/** 택배 접수 — 이미 접수됐거나 확인 중이면 다시 보내지 않는다 */
	public ShipmentExampleDto.ShipmentResponse register(ShipmentExampleDto.RegisterRequest request) {
		ValidateUtils.validate(request);
		ShipmentExampleDto.Shipment current = shipments.get(request.getOrderNo());
		if (current != null && current.getStatus() != ShipmentExampleDto.ShipmentStatus.FAILED) {
			return toResponse(current);
		}

		ShipmentExampleDto.Shipment shipment = ShipmentExampleDto.Shipment.builder()
																		  .orderNo(request.getOrderNo())
																		  .receiverName(request.getReceiverName().trim())
																		  .receiverMobileNo(request.getReceiverMobileNo().replaceAll("[^0-9]", ""))
																		  .address(request.getAddress().trim())
																		  .requestedAt(LocalDateTime.now())
																		  .build();
		try {
			ShipmentExampleDto.CourierShipment registered = WebClientUtils.post(courierClient, "/v1/shipments", null,
				ShipmentExampleDto.CourierRegisterRequest.builder()
														 .orderNo(shipment.getOrderNo())
														 .receiverName(shipment.getReceiverName())
														 .receiverMobileNo(shipment.getReceiverMobileNo())
														 .address(shipment.getAddress())
														 .build(),
				ShipmentExampleDto.CourierShipment.class).block();
			return save(registered(shipment, registered));
		} catch (RuntimeException e) {
			FailureType failureType = WebClientUtils.classify(e);
			if (failureType == FailureType.REJECTED) {
				CoreException rejected = courierError(e);
				save(shipment.toBuilder().status(ShipmentExampleDto.ShipmentStatus.FAILED).failMessage(rejected.getMessage()).build());
				throw rejected;
			}
			if (failureType == FailureType.NOT_SENT) {
				save(shipment.toBuilder().status(ShipmentExampleDto.ShipmentStatus.FAILED).failMessage("택배사 연결 실패").build());
				throw new CoreException(COURIER_UNAVAILABLE, COURIER_UNAVAILABLE.message(), e).addErrorLogData("orderNo", shipment.getOrderNo());
			}
			log.warn("택배 접수 결과불명 - 주문번호로 조회해 확인. orderNo={}", shipment.getOrderNo());
			return reconcile(shipment, e);
		}
	}

	/** 배송 추적 — GET 이라 실패하면 1회 재시도해도 안전하다 */
	public ShipmentExampleDto.TrackResponse track(String orderNo) {
		ShipmentExampleDto.Shipment shipment = find(orderNo);
		if (shipment.getStatus() != ShipmentExampleDto.ShipmentStatus.REGISTERED) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "택배 접수가 완료되지 않은 주문입니다.");
		}
		ShipmentExampleDto.CourierShipment tracked = callWithRetry(() -> WebClientUtils.get(courierClient,
			"/v1/shipments/track?invoiceNo={invoiceNo}", null, ShipmentExampleDto.CourierShipment.class, shipment.getInvoiceNo()).block());
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
	 * 조회도 실패하면 접수됐는지 모르는 상태로 두고 재접수를 막는다.
	 */
	private ShipmentExampleDto.ShipmentResponse reconcile(ShipmentExampleDto.Shipment shipment, Throwable registerError) {
		try {
			ShipmentExampleDto.CourierShipment found = callWithRetry(() -> WebClientUtils.get(courierClient,
				"/v1/shipments/by-order?orderNo={orderNo}", null, ShipmentExampleDto.CourierShipment.class, shipment.getOrderNo()).block());
			log.info("택배 접수 확인됨(결과불명 복구). orderNo={}, invoiceNo={}", shipment.getOrderNo(), found.getInvoiceNo());
			return save(registered(shipment, found));
		} catch (CoreException e) {
			if (e.getErrorCode().status() == 404) {
				// 택배사에 없다 = 접수 안 됨 확정 — 다시 접수해도 된다
				save(shipment.toBuilder().status(ShipmentExampleDto.ShipmentStatus.FAILED).failMessage("접수되지 않음 - 다시 시도하세요").build());
				throw new CoreException(COURIER_UNAVAILABLE, "택배 접수가 되지 않았습니다. 다시 시도해 주세요.", registerError).addErrorLogData("orderNo", shipment.getOrderNo());
			}
			save(shipment.toBuilder().status(ShipmentExampleDto.ShipmentStatus.CHECK_REQUIRED).failMessage(COURIER_UNKNOWN.message()).build());
			throw new CoreException(COURIER_UNKNOWN, COURIER_UNKNOWN.message(), registerError).addErrorLogData("orderNo", shipment.getOrderNo());
		}
	}

	/** GET 호출 — 거절(4xx)은 그대로, 나머지(미전송·결과불명)는 1회 재시도 후 실패 */
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

	/** 4xx 본문의 택배사 오류코드를 COURIER_{code} 로 넘긴다 */
	private static CoreException courierError(Throwable e) {
		Optional<WebClientResponseException> clientError = WebClientUtils.findClientError(e);
		ShipmentExampleDto.CourierError body = clientError.map(error -> {
			try {
				return error.getResponseBodyAs(ShipmentExampleDto.CourierError.class);
			} catch (RuntimeException parseError) {
				return null;
			}
		}).orElse(null);
		int status = clientError.map(error -> error.getStatusCode().value()).orElse(400);
		String code = body == null ? "REJECTED" : StringUtils.defaultIfBlank(body.getCode(), "REJECTED");
		String message = body == null ? "택배사가 요청을 거절했습니다." : StringUtils.defaultIfBlank(body.getMessage(), "택배사가 요청을 거절했습니다.");
		return new CoreException(ErrorCode.of("COURIER_" + code, message, status), message);
	}

	private static ShipmentExampleDto.Shipment registered(ShipmentExampleDto.Shipment shipment, ShipmentExampleDto.CourierShipment courier) {
		if (courier == null || StringUtils.isBlank(courier.getInvoiceNo())) {
			throw new CoreException(COURIER_UNKNOWN).addErrorLogData("orderNo", shipment.getOrderNo());
		}
		return shipment.toBuilder().status(ShipmentExampleDto.ShipmentStatus.REGISTERED).invoiceNo(courier.getInvoiceNo()).failMessage(null).build();
	}

	private ShipmentExampleDto.ShipmentResponse save(ShipmentExampleDto.Shipment shipment) {
		shipments.put(shipment.getOrderNo(), shipment);
		return toResponse(shipment);
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
