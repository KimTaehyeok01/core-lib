package com.corelib.example.web.service;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.example.web.dto.InquiryExampleDto;
import com.corelib.lang.DateTimeUtils;
import com.corelib.lang.MaskingUtils;
import com.corelib.web.TraceIdFilter;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 고객 문의 서비스.
 * <ul>
 *   <li>접수 — 클라이언트 IP(ClientIpUtils)로 1분 접수 건수를 제한해 도배를 막는다</li>
 *   <li>접수 건에 traceId 를 남긴다 — 고객이 알려 준 추적 번호로 서버 로그를 바로 찾는다</li>
 *   <li>접수 알림은 @Async 로 보낸다 — 다른 스레드 로그에도 같은 traceId 가 찍힌다</li>
 *   <li>관리자 목록은 이름·이메일 마스킹</li>
 * </ul>
 */
@Slf4j
@Service
public class InquiryExampleService {

	private static final ErrorCode TOO_MANY = ErrorCode.of("INQUIRY_TOO_MANY", "잠시 후 다시 문의해 주세요.", 429);
	private static final ErrorCode ALREADY_ANSWERED = ErrorCode.of("INQUIRY_ALREADY_ANSWERED", "이미 답변한 문의입니다.", 409);

	/**
	 * 실서비스는 문의 테이블 — 예제라 메모리에 둔다.
	 * 실서비스는 DB 조건부 UPDATE(WHERE status = 'RECEIVED') 로 같은 원자성 확보
	 */
	private final Map<Long, InquiryExampleDto.Inquiry> inquiries = new ConcurrentHashMap<>();
	/** 실서비스는 Redis(만료 키) — IP 별 최근 접수 시각. deque 는 이 맵의 compute 안에서만 읽고 고친다 */
	private final Map<String, Deque<LocalDateTime>> recentByIp = new ConcurrentHashMap<>();
	private final AtomicLong sequence = new AtomicLong();
	private final InquiryNotificationService notificationService;
	private final int limitPerMinute;

	public InquiryExampleService(InquiryNotificationService notificationService,
								 @Value("${example.inquiry.limit-per-minute:3}") int limitPerMinute) {
		this.notificationService = notificationService;
		this.limitPerMinute = limitPerMinute;
	}

	public InquiryExampleDto.CreateResponse create(InquiryExampleDto.CreateRequest request, String clientIp) {
		ValidateUtils.validate(request);
		LocalDateTime now = DateTimeUtils.now();
		checkRate(clientIp, now);

		InquiryExampleDto.Inquiry inquiry = InquiryExampleDto.Inquiry.builder()
																	 .inquiryNo(sequence.incrementAndGet())
																	 .name(request.getName().trim())
																	 .email(request.getEmail().trim())
																	 .content(request.getContent().strip())
																	 .clientIp(clientIp)
																	 .traceId(TraceIdFilter.current())
																	 .status(InquiryExampleDto.Status.RECEIVED)
																	 .receivedAt(now)
																	 .build();
		inquiries.put(inquiry.getInquiryNo(), inquiry);
		log.info("문의 접수. inquiryNo={}", inquiry.getInquiryNo());
		notificationService.notifyReceived(inquiry.getInquiryNo(), inquiry.getEmail());

		return InquiryExampleDto.CreateResponse.builder()
											   .inquiryNo(inquiry.getInquiryNo())
											   .traceId(inquiry.getTraceId())
											   .receivedAt(DateTimeUtils.format(now))
											   .build();
	}

	/** 관리자 — 최신순, 개인정보 마스킹 */
	public List<InquiryExampleDto.InquirySummary> list() {
		return inquiries.values().stream()
						.sorted(Comparator.comparing(InquiryExampleDto.Inquiry::getInquiryNo).reversed())
						.map(inquiry -> InquiryExampleDto.InquirySummary.builder()
																		.inquiryNo(inquiry.getInquiryNo())
																		.maskedName(MaskingUtils.name(inquiry.getName()))
																		.maskedEmail(MaskingUtils.email(inquiry.getEmail()))
																		.content(inquiry.getContent())
																		.clientIp(inquiry.getClientIp())
																		.traceId(inquiry.getTraceId())
																		.status(inquiry.getStatus())
																		.receivedAt(DateTimeUtils.format(inquiry.getReceivedAt()))
																		.build())
						.toList();
	}

	/**
	 * 관리자 — 답변 등록. 상태 확인과 RECEIVED → ANSWERED 전이를 computeIfPresent 한 번으로 처리한다
	 * — 두 관리자가 동시에 답변해도 1건만 저장되고 나머지는 409 (나중 답변이 앞 답변을 덮지 않는다).
	 */
	public void answer(InquiryExampleDto.AnswerRequest request) {
		ValidateUtils.validate(request);
		// compute 안에서 던지면 저장값은 그대로 남는다
		InquiryExampleDto.Inquiry answered = inquiries.computeIfPresent(request.getInquiryNo(), (inquiryNo, inquiry) -> {
			if (inquiry.getStatus() == InquiryExampleDto.Status.ANSWERED) {
				throw new CoreException(ALREADY_ANSWERED).addErrorLogData("inquiryNo", inquiryNo);
			}
			return inquiry.toBuilder().status(InquiryExampleDto.Status.ANSWERED).answer(request.getAnswer()).build();
		});
		if (answered == null) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "문의를 찾을 수 없습니다.");
		}
	}

	/**
	 * 최근 1분 접수가 없는 IP 키를 지운다 — 한 번 왔다 간 IP 가 맵에 계속 쌓이지 않게.
	 * checkRate 와 같은 키 잠금(compute) 안에서 지우므로, 막 기록하려던 deque 를 지워 접수 기록을 잃는 일이 없다.
	 */
	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
	public void evictIdleIps() {
		LocalDateTime threshold = DateTimeUtils.now().minusMinutes(1);
		for (String clientIp : recentByIp.keySet()) {
			recentByIp.computeIfPresent(clientIp, (ip, recent) -> {
				pruneBefore(recent, threshold);
				return recent.isEmpty() ? null : recent;
			});
		}
	}

	/** 같은 IP 의 최근 1분 접수 건수가 상한이면 429 — 같은 IP 동시 접수는 compute 의 키 잠금으로 줄 세운다 */
	private void checkRate(String clientIp, LocalDateTime now) {
		recentByIp.compute(clientIp, (ip, recent) -> {
			Deque<LocalDateTime> window = recent == null ? new ArrayDeque<>() : recent;
			pruneBefore(window, now.minusMinutes(1));
			if (window.size() >= limitPerMinute) {
				throw new CoreException(TOO_MANY).addErrorLogData("clientIp", ip);
			}
			window.addLast(now);
			return window;
		});
	}

	private static void pruneBefore(Deque<LocalDateTime> recent, LocalDateTime threshold) {
		while (!recent.isEmpty() && recent.peekFirst().isBefore(threshold)) {
			recent.pollFirst();
		}
	}
}
