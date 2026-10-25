package com.corelib.common.startup;

import com.corelib.lang.StringUtils;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ansi.AnsiColor;
import org.springframework.boot.ansi.AnsiOutput;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * 기동 안내 출력.
 * <ul>
 *   <li>CORE 로고는 노란색 — 색은 Spring 의 ANSI 출력 설정(spring.output.ansi.enabled)을 따른다</li>
 *   <li>로그 한 번에 여러 줄로 찍는다 — 다른 로그와 섞이지 않게</li>
 *   <li>비밀값은 각 모듈의 {@link StartupReporter} 가 애초에 넘기지 않는다</li>
 * </ul>
 */
public class StartupLogPrinter implements ApplicationListener<ApplicationReadyEvent> {

	private static final Logger log = LoggerFactory.getLogger("core.startup");
	private static final String LINE = "=".repeat(72);
	private static final String THIN_LINE = "-".repeat(72);
	private static final String[] LOGO = {
		"   ____   ___   ____   _____ ",
		"  / ___| / _ \\ |  _ \\ | ____|",
		" | |    | | | || |_) ||  _|  ",
		" | |___ | |_| ||  _ < | |___ ",
		"  \\____| \\___/ |_| \\_\\|_____|"
	};

	private final List<StartupReporter> reporters;

	public StartupLogPrinter(List<StartupReporter> reporters) {
		this.reporters = reporters;
	}

	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		Integer port = event.getApplicationContext() instanceof WebServerApplicationContext web && web.getWebServer() != null
			? web.getWebServer().getPort() : null;
		log.info(render(event.getApplicationContext().getEnvironment(), port, networkAddress()));
	}

	/** @param port 웹 서버 포트 — 웹 앱이 아니면 null */
	String render(Environment environment, Integer port, String networkAddress) {
		StringBuilder out = new StringBuilder(System.lineSeparator()).append(LINE).append(System.lineSeparator());
		for (String row : LOGO) {
			out.append(AnsiOutput.toString(AnsiColor.YELLOW, row)).append(System.lineSeparator());
		}
		out.append(System.lineSeparator());

		String name = StringUtils.defaultIfBlank(environment.getProperty("spring.application.name"), "application");
		String[] profiles = environment.getActiveProfiles();
		out.append("  ").append(name).append(" 실행 완료 (profile: ")
		   .append(profiles.length == 0 ? "default" : String.join(", ", profiles)).append(")").append(System.lineSeparator());

		String base = null;
		if (port != null && port > 0) {
			String scheme = "true".equalsIgnoreCase(environment.getProperty("server.ssl.enabled")) ? "https" : "http";
			String contextPath = StringUtils.defaultIfBlank(environment.getProperty("server.servlet.context-path"), "");
			base = scheme + "://localhost:" + port + contextPath;
			line(out, "Local", base);
			if (networkAddress != null) {
				line(out, "Network", scheme + "://" + networkAddress + ":" + port + contextPath);
			}
		}

		List<StartupReport> reports = reporters.stream().map(StartupLogPrinter::reportOf).toList();
		for (StartupReport report : reports) {
			if (report.getPath() != null && base != null) {
				line(out, report.getName(), base + report.getPath());
			}
		}

		List<StartupReport> modules = reports.stream().filter(report -> report.getPath() == null).toList();
		if (!modules.isEmpty()) {
			out.append(THIN_LINE).append(System.lineSeparator());
			int width = modules.stream().mapToInt(report -> report.getName().length()).max().orElse(0);
			for (StartupReport report : modules) {
				String status = report.isReady() ? "초기화 완료" : AnsiOutput.toString(AnsiColor.RED, "미초기화");
				out.append("  ").append(AnsiOutput.toString(AnsiColor.YELLOW, "[core]")).append(' ')
				   .append(String.format("%-" + width + "s", report.getName())).append(' ').append(status);
				if (StringUtils.isNotBlank(report.getDetail())) {
					out.append(" — ").append(report.getDetail());
				}
				out.append(System.lineSeparator());
			}
		}
		return out.append(LINE).toString();
	}

	/** 모듈 하나의 안내 실패가 앱 기동을 막지 않게 — 예외·null 은 미초기화로 바꿔 찍고 원인은 로그로 남긴다 */
	private static StartupReport reportOf(StartupReporter reporter) {
		String name = reporterName(reporter);
		try {
			StartupReport report = reporter.report();
			if (report != null) {
				return report;
			}
			log.warn("기동 안내 생성 실패 - reporter={}, report=null", name);
		} catch (RuntimeException e) {
			log.warn("기동 안내 생성 실패 - reporter={}", name, e);
		}
		return StartupReport.notReady(name, "안내 생성 실패");
	}

	/** 실패한 reporter 를 알아볼 이름 — 람다(@Bean 메서드)면 선언한 클래스 이름 */
	private static String reporterName(StartupReporter reporter) {
		Class<?> type = reporter.getClass();
		String name = StringUtils.defaultIfBlank(type.getSimpleName(), type.getName());
		int lambda = name.indexOf("$$");
		return lambda > 0 ? name.substring(0, lambda) : name;
	}

	private static void line(StringBuilder out, String label, String value) {
		out.append(String.format("  %-8s: %s", label, value)).append(System.lineSeparator());
	}

	/** 같은 네트워크의 다른 기기(휴대폰 등)에서 접속할 주소 — 사설 IPv4 하나, 없으면 null */
	private static String networkAddress() {
		try {
			for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
					continue;
				}
				for (InetAddress address : Collections.list(network.getInetAddresses())) {
					if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
						return address.getHostAddress();
					}
				}
			}
		} catch (SocketException e) {
			log.debug("네트워크 주소 조회 실패", e);
		}
		return null;
	}
}
