package com.corelib.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpUtilsTest {

	@AfterEach
	void reset() {
		ClientIpUtils.initialize(new WebProperty.ClientIp());
	}

	private static MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(remoteAddr);
		if (forwardedFor != null) {
			request.addHeader("X-Forwarded-For", forwardedFor);
		}
		return request;
	}

	@Test
	@DisplayName("신뢰 프록시(사설망) 뒤 — X-Forwarded-For 를 오른쪽부터 따라가 첫 외부 IP")
	void behindTrustedProxy() {
		assertThat(ClientIpUtils.resolve(request("10.0.0.5", "203.0.113.7"))).isEqualTo("203.0.113.7");
		// 클라이언트가 맨 왼쪽에 가짜 값을 넣어도, 프록시가 붙인 실제 주소(오른쪽)를 쓴다
		assertThat(ClientIpUtils.resolve(request("10.0.0.5", "1.1.1.1, 203.0.113.7, 10.0.0.9"))).isEqualTo("203.0.113.7");
		assertThat(ClientIpUtils.resolve(request("10.0.0.5", "203.0.113.7:51234"))).isEqualTo("203.0.113.7");
		assertThat(ClientIpUtils.resolve(request("10.0.0.5", "[2001:db8::1]:443"))).isEqualTo("2001:db8::1");
	}

	@Test
	@DisplayName("공인 IP 가 직접 접속 — 헤더는 위조일 수 있어 무시")
	void directPublicClient() {
		assertThat(ClientIpUtils.resolve(request("198.51.100.20", "127.0.0.1"))).isEqualTo("198.51.100.20");
	}

	@Test
	@DisplayName("IP 가 아닌 값은 버린다 — 호스트 이름·로그 주입 문자열, 깨진 값부터 왼쪽은 믿지 않음")
	void invalidValues() {
		assertThat(ClientIpUtils.resolve(request("10.0.0.5", "evil.example.com"))).isEqualTo("10.0.0.5");
		assertThat(ClientIpUtils.resolve(request("10.0.0.5", "203.0.113.7, bad\nlog"))).isEqualTo("10.0.0.5");
		assertThat(ClientIpUtils.normalize("999.1.1.1")).isNull();
		assertThat(ClientIpUtils.normalize(" 192.168.0.1 ")).isEqualTo("192.168.0.1");
	}

	@Test
	@DisplayName("내부망끼리 — 전부 신뢰 프록시면 맨 왼쪽, 헤더 없으면 remoteAddr")
	void internal() {
		assertThat(ClientIpUtils.resolve(request("10.0.0.5", "192.168.0.10, 10.0.0.9"))).isEqualTo("192.168.0.10");
		assertThat(ClientIpUtils.resolve(request("127.0.0.1", null))).isEqualTo("127.0.0.1");
	}

	@Test
	@DisplayName("신뢰 대역 설정 — /prefix 경계, 잘못된 CIDR 은 기동 시 오류")
	void trustedProxyConfig() {
		WebProperty.ClientIp property = new WebProperty.ClientIp();
		property.setTrustedProxies(List.of("172.16.0.0/12"));
		ClientIpUtils.initialize(property);

		assertThat(ClientIpUtils.isTrusted("172.31.255.255")).isTrue();
		assertThat(ClientIpUtils.isTrusted("172.32.0.1")).isFalse();
		assertThat(ClientIpUtils.resolve(request("10.0.0.5", "203.0.113.7"))).isEqualTo("10.0.0.5");

		property.setTrustedProxies(List.of("not-an-ip/8"));
		assertThatThrownBy(() -> ClientIpUtils.initialize(property)).isInstanceOf(IllegalArgumentException.class);
	}
}
