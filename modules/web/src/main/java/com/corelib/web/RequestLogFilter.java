package com.corelib.web;

import com.corelib.lang.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * 요청 한 건당 로그 한 줄 — "POST /api/orders 200 35ms ip=1.2.3.4" (traceId 는 로그 패턴의 MDC 로 붙는다).
 * <ul>
 *   <li>쿼리스트링은 찍지 않는다 — 개인정보·토큰이 실려 올 수 있다</li>
 *   <li>본문은 include-body=true 일 때 JSON 만 찍는다 — JSON 으로 읽어 키 이름이 mask-keys 로 끝나면 값을 통째로(배열·객체 포함) "***"</li>
 *   <li>body-max-length 를 넘었거나 JSON 으로 읽히지 않는 본문은 내용 없이 "(N bytes)" 만 — 잘린 값이 그대로 새지 않게</li>
 *   <li>응답은 버퍼링하지 않는다 — JSON 이면 나가는 바이트의 앞부분(body-max-length)만 복사해 두고, 그 외(다운로드·SSE)는 손대지 않는다</li>
 *   <li>5xx 는 WARN, 나머지는 INFO</li>
 * </ul>
 */
public class RequestLogFilter extends OncePerRequestFilter {

	static final String MASK = "***";

	private static final Logger log = LoggerFactory.getLogger("core.request");
	private static final AntPathMatcher PATHS = new AntPathMatcher();
	/** 앱 ObjectMapper 설정(들여쓰기 등)에 흔들리지 않게 따로 둔다 — 트리 읽기·쓰기만 한다 */
	private static final ObjectMapper JSON = new ObjectMapper();

	private final WebProperty.RequestLog property;
	private final List<String> maskKeys;

	public RequestLogFilter(WebProperty.RequestLog property) {
		this.property = property;
		this.maskKeys = property.getMaskKeys() == null ? List.of()
			: property.getMaskKeys().stream().filter(StringUtils::isNotBlank).map(RequestLogFilter::normalizeMaskKey).toList();
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
		CachedRequest cachedRequest = null;
		CapturedResponse capturedResponse = null;
		if (property.isIncludeBody()) {
			if (isJson(request.getContentType())) {
				cachedRequest = new CachedRequest(request, property.getBodyMaxLength());
			}
			capturedResponse = new CapturedResponse(response, property.getBodyMaxLength());
		}
		try {
			chain.doFilter(cachedRequest == null ? request : cachedRequest, capturedResponse == null ? response : capturedResponse);
		} finally {
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
			int status = response.getStatus();
			String line = String.format("%s %s %d %dms ip=%s", request.getMethod(), request.getRequestURI(), status, elapsedMs,
				ClientIpUtils.resolve(request));
			if (capturedResponse != null) {
				line += " req=" + requestBody(request, cachedRequest) + " res=" + responseBody(capturedResponse);
			}
			if (status >= 500) {
				log.warn(line);
			} else {
				log.info(line);
			}
		}
	}

	private String requestBody(HttpServletRequest request, CachedRequest cached) {
		if (cached == null) {
			return size(request.getContentLengthLong());
		}
		return body(cached.getContentAsByteArray(), cached.isOverflow, request.getContentLengthLong());
	}

	private String responseBody(CapturedResponse response) {
		if (response.captureStream == null) {
			return size(contentLength(response));
		}
		return body(response.captured.toByteArray(), response.length > response.limit, response.length);
	}

	/**
	 * JSON 본문 → 마스킹한 한 줄. 상한을 넘어 잘렸거나 JSON 으로 읽히지 않으면 내용 없이 크기만.
	 *
	 * @param isTruncated 상한을 넘어 일부만 담겼는지
	 * @param length      전체 바이트 수 (모르면 음수)
	 */
	String body(byte[] content, boolean isTruncated, long length) {
		if (isTruncated || content.length == 0) {
			return size(Math.max(length, content.length));
		}
		try {
			JsonNode node = JSON.readTree(content);
			if (node == null || node.isMissingNode()) {
				return size(content.length);
			}
			return StringUtils.truncate(JSON.writeValueAsString(mask(node)), property.getBodyMaxLength());
		} catch (IOException e) {
			return size(content.length);    // JSON 이 아니거나 깨진 본문 — 내용은 찍지 않는다
		}
	}

	/** 키 이름이 mask-keys 로 끝나면 값을 통째로 "***" — 아니면 안쪽 객체·배열까지 내려간다 */
	private JsonNode mask(JsonNode node) {
		if (node instanceof ObjectNode object) {
			for (Map.Entry<String, JsonNode> field : object.properties()) {
				if (isMaskKey(field.getKey())) {
					field.setValue(TextNode.valueOf(MASK));
				} else {
					mask(field.getValue());
				}
			}
		} else if (node.isArray()) {
			node.forEach(this::mask);
		}
		return node;
	}

	private boolean isMaskKey(String key) {
		String name = normalizeMaskKey(key);
		return maskKeys.stream().anyMatch(name::endsWith);
	}

	/** 비교 전 `_`·`-` 를 걷어내고 소문자로 — phone_number · phone-number · phoneNumber 를 같은 키로 본다 */
	private static String normalizeMaskKey(String key) {
		return key.replace("_", "").replace("-", "").trim().toLowerCase(Locale.ROOT);
	}

	private static boolean isJson(String contentType) {
		return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json");
	}

	private static long contentLength(HttpServletResponse response) {
		try {
			String value = response.getHeader(HttpHeaders.CONTENT_LENGTH);
			return value == null ? -1 : Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** "-" 는 본문 없음 또는 크기를 알 수 없음 */
	private static String size(long length) {
		return length <= 0 ? "-" : "(" + length + " bytes)";
	}

	/** 상한을 넘었는지 알 수 있는 요청 캐시 — 넘으면 본문을 찍지 않는다 */
	private static final class CachedRequest extends ContentCachingRequestWrapper {

		private boolean isOverflow;

		CachedRequest(HttpServletRequest request, int limit) {
			super(request, limit);
		}

		@Override
		protected void handleContentOverflow(int contentCacheLimit) {
			isOverflow = true;
		}
	}

	/**
	 * 응답을 붙잡아 두지 않는 래퍼 — 본문을 처음 쓸 때 Content-Type 이 JSON 이면 그대로 내보내면서 앞부분 limit 바이트만 복사하고,
	 * 아니면 원래 스트림을 그대로 준다 (다운로드·SSE 스트리밍 유지). getWriter 로 쓴 본문은 복사하지 않는다.
	 */
	private static final class CapturedResponse extends HttpServletResponseWrapper {

		private final int limit;
		private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
		private long length;
		private ServletOutputStream captureStream;

		CapturedResponse(HttpServletResponse response, int limit) {
			super(response);
			this.limit = Math.max(limit, 0);
		}

		@Override
		public ServletOutputStream getOutputStream() throws IOException {
			if (captureStream != null) {
				return captureStream;
			}
			ServletOutputStream target = super.getOutputStream();
			if (!isJson(getContentType())) {
				return target;
			}
			captureStream = new CaptureOutputStream(target);
			return captureStream;
		}

		@Override
		public void resetBuffer() {
			super.resetBuffer();
			clear();
		}

		@Override
		public void reset() {
			super.reset();
			clear();
		}

		private void clear() {
			captured.reset();
			length = 0;
		}

		private void capture(byte[] bytes, int offset, int count) {
			length += count;
			int room = limit - captured.size();
			if (room > 0) {
				captured.write(bytes, offset, Math.min(count, room));
			}
		}

		/** 받은 바이트를 바로 원래 스트림으로 보내고 앞부분만 복사한다 */
		private final class CaptureOutputStream extends ServletOutputStream {

			private final ServletOutputStream target;

			CaptureOutputStream(ServletOutputStream target) {
				this.target = target;
			}

			@Override
			public void write(int b) throws IOException {
				target.write(b);
				length++;
				if (captured.size() < limit) {
					captured.write(b);
				}
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				target.write(b, off, len);
				capture(b, off, len);
			}

			@Override
			public void flush() throws IOException {
				target.flush();
			}

			@Override
			public void close() throws IOException {
				target.close();
			}

			@Override
			public boolean isReady() {
				return target.isReady();
			}

			@Override
			public void setWriteListener(WriteListener writeListener) {
				target.setWriteListener(writeListener);
			}
		}
	}
}
