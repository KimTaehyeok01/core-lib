// 기동 안내에 찍을 모듈 한 줄 (초기화 완료 / 미초기화 / 화면 주소)
package com.corelib.common.startup;

import java.time.Duration;

/**
 * 기동 안내 한 줄.
 * <ul>
 *   <li>{@link #ready} — 초기화 완료, detail 에 접속 주소·timeout 등 (비밀값 금지)</li>
 *   <li>{@link #notReady} — 설정이 없어 꺼짐, reason 에 무엇을 넣으면 되는지</li>
 *   <li>{@link #link} — 앱 주소 뒤에 붙는 화면 경로 (Swagger UI 등)</li>
 * </ul>
 */
public final class StartupReport {

	private final String name;
	private final boolean isReady;
	private final String detail;
	private final String path;

	private StartupReport(String name, boolean isReady, String detail, String path) {
		this.name = name;
		this.isReady = isReady;
		this.detail = detail;
		this.path = path;
	}

	public static StartupReport ready(String name, String detail) {
		return new StartupReport(name, true, detail, null);
	}

	public static StartupReport notReady(String name, String reason) {
		return new StartupReport(name, false, reason, null);
	}

	/** @param path 컨텍스트 경로 뒤의 경로 (예: /swagger-ui.html) */
	public static StartupReport link(String name, String path) {
		return new StartupReport(name, true, null, path);
	}

	/** @return "timeout=5s/30s" (연결/응답) */
	public static String timeout(Duration connectTimeout, Duration readTimeout) {
		return "timeout=" + format(connectTimeout) + "/" + format(readTimeout);
	}

	private static String format(Duration duration) {
		if (duration == null) {
			return "-";
		}
		return duration.toMillis() % 1000 == 0 ? duration.toSeconds() + "s" : duration.toMillis() + "ms";
	}

	public String getName() {
		return name;
	}

	public boolean isReady() {
		return isReady;
	}

	public String getDetail() {
		return detail;
	}

	public String getPath() {
		return path;
	}
}
