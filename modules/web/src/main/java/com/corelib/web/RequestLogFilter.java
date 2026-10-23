package com.corelib.web;

import com.corelib.lang.StringUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 요청 한 건당 로그 한 줄 — "POST /api/orders 200 35ms ip=1.2.3.4" (traceId 는 로그 패턴의 MDC 로 붙는다).
 * <ul>
 *   <li>쿼리스트링은 찍지 않는다 — 개인정보·토큰이 실려 올 수 있다</li>
 *   <li>본문은 include-body=true 일 때만, JSON 만, mask-keys 의 값을 가리고 길이를 잘라 찍는다</li>
 *   <li>5xx 는 WARN, 나머지는 INFO</li>
 * </ul>
 */
public class RequestLogFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger("core.request");
	private static final AntPathMatcher PATHS = new AntPathMatcher();

	private final WebProperty.RequestLog property;
	private final Pattern maskPattern;

	public RequestLogFilter(WebProperty.RequestLog property) {
		this.property = property;
		this.maskPattern = maskPattern(property.getMaskKeys());
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return property.getExcludePaths().stream().anyMatch(pattern -> PATHS.match(pattern, path));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long startedAt = System.nanoTime();
		HttpServletRequest target = request;
		ContentCachingResponseWrapper cachedResponse = null;
		if (property.isIncludeBody()) {
			target = new ContentCachingRequestWrapper(request, property.getBodyMaxLength());
			cachedResponse = new ContentCachingResponseWrapper(response);
		}
		try {
			chain.doFilter(target, cachedResponse == null ? response : cachedResponse);
		} finally {
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
			int status = (cachedResponse == null ? response : cachedResponse).getStatus();
			String line = String.format("%s %s %d %dms ip=%s", request.getMethod(), request.getRequestURI(), status, elapsedMs,
				ClientIpUtils.resolve(request));
			if (cachedResponse != null) {
				line += " req=" + body(((ContentCachingRequestWrapper) target).getContentAsByteArray(), request.getContentType())
					+ " res=" + body(cachedResponse.getContentAsByteArray(), cachedResponse.getContentType());
				cachedResponse.copyBodyToResponse();    // 캐시한 응답 본문을 실제로 내보낸다 — 빠뜨리면 빈 응답
			}
			if (status >= 500) {
				log.warn(line);
			} else {
				log.info(line);
			}
		}
	}

	private String body(byte[] content, String contentType) {
		if (content.length == 0) {
			return "-";
		}
		if (contentType == null || !contentType.toLowerCase().contains("json")) {
			return "(" + content.length + " bytes)";
		}
		String text = new String(content, StandardCharsets.UTF_8).replaceAll("\\s*\\R\\s*", " ");
		return StringUtils.truncate(mask(text), property.getBodyMaxLength());
	}

	/** "password":"abc" → "password":"****", "cardNo":1234 → "cardNo":"****" */
	String mask(String json) {
		if (maskPattern == null) {
			return json;
		}
		Matcher matcher = maskPattern.matcher(json);
		return matcher.replaceAll(result -> Matcher.quoteReplacement(result.group(1) + "\"****\""));
	}

	private static Pattern maskPattern(List<String> keys) {
		if (keys == null || keys.isEmpty()) {
			return null;
		}
		String names = String.join("|", keys.stream().map(Pattern::quote).toList());
		// 키 이름이 마스킹 단어로 끝나면(userCi·newPassword) 문자열·숫자·true/false·null 값을 가린다 — price·city 는 안 가린다
		return Pattern.compile("(\"[^\"]*?(?:" + names + ")\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d[\\d.eE+-]*|true|false|null)",
			Pattern.CASE_INSENSITIVE);
	}
}
