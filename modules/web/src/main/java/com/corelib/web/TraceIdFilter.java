package com.corelib.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 traceId 를 정해 로그(MDC)·응답 헤더에 싣는다.
 * 앞단(게이트웨이·프론트)이 헤더로 준 값이 형식에 맞으면 이어서 쓰고, 아니면 새로 만든다 — 로그 주입을 막으려고 형식을 제한한다.
 */
public class TraceIdFilter extends OncePerRequestFilter {

	/** MDC 키 — 로그 패턴에서 %X{traceId} 로 쓴다 */
	public static final String MDC_KEY = "traceId";
	private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

	private final String header;

	public TraceIdFilter(String header) {
		this.header = header;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String incoming = request.getHeader(header);
		String traceId = incoming != null && ALLOWED.matcher(incoming).matches() ? incoming : newTraceId();
		MDC.put(MDC_KEY, traceId);
		response.setHeader(header, traceId);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove(MDC_KEY);
		}
	}

	/** @return 지금 요청의 traceId — 요청 밖(스케줄러 등)이면 null */
	public static String current() {
		return MDC.get(MDC_KEY);
	}

	private static String newTraceId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
	}
}
