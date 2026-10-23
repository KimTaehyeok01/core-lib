package com.corelib.web;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * @Async·스프링 TaskExecutor 로 넘긴 작업에 호출한 스레드의 MDC(traceId 등)를 복사한다.
 * 작업이 끝나면 그 스레드의 원래 MDC 로 되돌린다 — 스레드 풀에서 다음 작업에 값이 새지 않게.
 */
public class MdcTaskDecorator implements TaskDecorator {

	@Override
	public Runnable decorate(Runnable runnable) {
		Map<String, String> captured = MDC.getCopyOfContextMap();
		return () -> {
			Map<String, String> previous = MDC.getCopyOfContextMap();
			setContext(captured);
			try {
				runnable.run();
			} finally {
				setContext(previous);
			}
		};
	}

	private static void setContext(Map<String, String> context) {
		if (context == null) {
			MDC.clear();
		} else {
			MDC.setContextMap(context);
		}
	}
}
