package com.corelib.example.webclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 택배 예제 데이터 전송 객체 */
public class ShipmentExampleDto {

	/** 우리 쪽 배송 상태 */
	public enum ShipmentStatus {
		/** 택배사에 접수 요청 중 — 이 상태를 선점한 요청만 택배사를 호출한다. 실서비스는 오래된 PENDING 을 배치가 주문번호 조회로 확정 */
		PENDING,
		/** 택배사 접수 완료 — 송장번호 있음 */
		REGISTERED,
		/** 접수 실패 (거절·미전송) — 다시 접수할 수 있다 */
		FAILED,
		/** 접수됐는지 모름 — 재접수 금지, 관리자 확인 */
		CHECK_REQUIRED
	}

	// === 택배 접수 요청 (우리 API) ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class RegisterRequest {

		@NotBlank
		@Size(max = 40)
		private String orderNo;

		@NotBlank
		@Size(max = 30)
		private String receiverName;

		@NotBlank
		@Pattern(regexp = "01[0-9]-?[0-9]{3,4}-?[0-9]{4}", message = "휴대폰번호 형식이 아닙니다.")
		private String receiverMobileNo;

		@NotBlank
		@Size(max = 200)
		private String address;
	}

	// === 배송 (저장 모델) — 실서비스는 배송 테이블 ===
	@Getter
	@Builder(toBuilder = true)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Shipment {

		private final String orderNo;

		private final String receiverName;

		private final String receiverMobileNo;

		private final String address;

		private final ShipmentStatus status;

		/** 택배사 송장번호 */
		private final String invoiceNo;

		private final String failMessage;

		private final LocalDateTime requestedAt;
	}

	// === 배송 응답 (마스킹) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class ShipmentResponse {

		private final String orderNo;

		private final ShipmentStatus status;

		private final String invoiceNo;

		private final String maskedReceiverName;

		private final String maskedReceiverMobileNo;

		private final String failMessage;
	}

	// === 배송 추적 응답 (우리 API) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class TrackResponse {

		private final String orderNo;

		private final String invoiceNo;

		/** 택배사 상태 원문 (ACCEPTED / IN_TRANSIT / DELIVERED) */
		private final String deliveryStatus;

		private final List<TrackingEvent> events;
	}

	// ================= 택배사 API 규격 =================

	// === 택배사 접수 요청 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class CourierRegisterRequest {

		/** 우리 주문번호 — 택배사가 중복 접수를 막는 키 */
		private String orderNo;

		private String receiverName;

		private String receiverMobileNo;

		private String address;
	}

	// === 택배사 접수·조회 응답 ===
	@Getter
	@Setter
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class CourierShipment {

		private String invoiceNo;

		private String orderNo;

		private String status;

		private List<TrackingEvent> events;
	}

	// === 배송 이력 한 줄 ===
	@Getter
	@Setter
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class TrackingEvent {

		private String occurredAt;

		private String location;

		private String description;
	}

	// === 택배사 오류 본문 (4xx) ===
	@Getter
	@Setter
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class CourierError {

		private String code;

		private String message;
	}
}
