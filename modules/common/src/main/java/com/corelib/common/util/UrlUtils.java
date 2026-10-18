// URL 스킴 검증 유틸 - 외부 연동 호스트의 TLS 강제 (로컬 테스트 서버만 http 허용)
package com.corelib.common.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

public final class UrlUtils {

	private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

	private UrlUtils() {
	}

	/**
	 * https 이거나, 내 PC(localhost·127.0.0.1·::1)를 가리키는 http 면 true.
	 * 예제·테스트의 로컬 가짜 서버는 허용하고, 실제 외부 호스트로 키가 평문 전송되는 오설정은 막는다.
	 */
	public static boolean isHttpsOrLoopback(String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		URI uri;
		try {
			uri = new URI(url.trim());
		} catch (URISyntaxException e) {
			return false;
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (scheme.equals("https")) {
			return uri.getHost() != null;
		}
		return scheme.equals("http") && uri.getHost() != null && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
	}
}
