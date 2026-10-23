package com.corelib.example.web.service;

import com.corelib.lang.MaskingUtils;
import com.corelib.web.TraceIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 접수 알림 — 요청 스레드를 붙잡지 않도록 @Async 로 보낸다.
 * web 모듈의 MdcTaskDecorator 덕분에 이 스레드 로그에도 요청과 같은 traceId 가 붙는다.
 * 실서비스는 여기서 MailUtils.sendHtml 로 접수 확인 메일을 보낸다.
 */
@Slf4j
@Service
public class InquiryNotificationService {

	@Async
	public void notifyReceived(Long inquiryNo, String email) {
		log.info("문의 접수 알림 발송. inquiryNo={}, to={}, thread={}, traceId={}",
			inquiryNo, MaskingUtils.email(email), Thread.currentThread().getName(), TraceIdFilter.current());
	}
}
