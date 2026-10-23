package com.corelib.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UrlUtilsTest {

	@Test
	@DisplayName("https 와 로컬 http 는 허용, 외부 http·형식 오류는 거부")
	void isHttpsOrLoopback() {
		assertThat(UrlUtils.isHttpsOrLoopback("https://api.tosspayments.com")).isTrue();
		assertThat(UrlUtils.isHttpsOrLoopback("http://localhost:8080")).isTrue();
		assertThat(UrlUtils.isHttpsOrLoopback("http://127.0.0.1:51234")).isTrue();
		assertThat(UrlUtils.isHttpsOrLoopback("http://[::1]:9000")).isTrue();

		assertThat(UrlUtils.isHttpsOrLoopback("http://api.tosspayments.com")).isFalse();
		assertThat(UrlUtils.isHttpsOrLoopback("http://localhost.evil.com")).isFalse();
		assertThat(UrlUtils.isHttpsOrLoopback("ftp://localhost")).isFalse();
		assertThat(UrlUtils.isHttpsOrLoopback("not a url")).isFalse();
		assertThat(UrlUtils.isHttpsOrLoopback(null)).isFalse();
	}
}
