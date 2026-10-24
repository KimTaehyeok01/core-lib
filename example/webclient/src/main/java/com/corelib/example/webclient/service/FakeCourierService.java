package com.corelib.example.webclient.service;

import com.corelib.example.webclient.dto.ShipmentExampleDto;
import com.corelib.lang.RandomStringUtils;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 가짜 택배사 (외부 시스템 역할) — 실서비스에는 없다.
 * 주문번호당 한 번만 접수하고(중복은 거절), 조회할 때마다 배송이 한 단계씩 진행된다.
 */
@Service
public class FakeCourierService {

	private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final List<String[]> STEPS = List.of(
		new String[] {"ACCEPTED", "서울 송파 집하점", "집하 완료"},
		new String[] {"IN_TRANSIT", "대전 허브", "간선 상차"},
		new String[] {"DELIVERED", "배송지", "배송 완료"});

	private final Map<String, String> invoiceByOrderNo = new ConcurrentHashMap<>();
	private final Map<String, Integer> progressByInvoiceNo = new ConcurrentHashMap<>();

	/** @return 접수된 송장 — 이미 접수된 주문번호면 empty */
	public Optional<ShipmentExampleDto.CourierShipment> register(ShipmentExampleDto.CourierRegisterRequest request) {
		String invoiceNo = "INV" + RandomStringUtils.randomNumeric(10);
		// 진행 단계를 먼저 넣고 주문번호를 연결한다 — 주문번호로는 찾히는데 진행 단계가 없는 틈(조회 NPE)이 생기지 않게
		progressByInvoiceNo.put(invoiceNo, 0);
		if (invoiceByOrderNo.putIfAbsent(request.getOrderNo(), invoiceNo) != null) {
			progressByInvoiceNo.remove(invoiceNo);
			return Optional.empty();
		}
		return Optional.of(shipment(request.getOrderNo(), invoiceNo, 0));
	}

	public Optional<ShipmentExampleDto.CourierShipment> findByOrderNo(String orderNo) {
		String invoiceNo = invoiceByOrderNo.get(orderNo);
		return invoiceNo == null ? Optional.empty() : Optional.of(shipment(orderNo, invoiceNo, progressByInvoiceNo.get(invoiceNo)));
	}

	/** 조회할 때마다 한 단계 진행 */
	public Optional<ShipmentExampleDto.CourierShipment> track(String invoiceNo) {
		Integer progress = progressByInvoiceNo.computeIfPresent(invoiceNo, (key, step) -> Math.min(step + 1, STEPS.size() - 1));
		if (progress == null) {
			return Optional.empty();
		}
		String orderNo = invoiceByOrderNo.entrySet().stream().filter(entry -> entry.getValue().equals(invoiceNo))
										 .map(Map.Entry::getKey).findFirst().orElse(null);
		return Optional.of(shipment(orderNo, invoiceNo, progress));
	}

	private static ShipmentExampleDto.CourierShipment shipment(String orderNo, String invoiceNo, int progress) {
		ShipmentExampleDto.CourierShipment shipment = new ShipmentExampleDto.CourierShipment();
		shipment.setOrderNo(orderNo);
		shipment.setInvoiceNo(invoiceNo);
		shipment.setStatus(STEPS.get(progress)[0]);
		String now = LocalDateTime.now().format(EVENT_TIME);
		shipment.setEvents(STEPS.subList(0, progress + 1).stream()
								.map(step -> new ShipmentExampleDto.TrackingEvent(now, step[1], step[2]))
								.toList());
		return shipment;
	}
}
