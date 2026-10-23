package com.corelib.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoundUtilsTest {

	@Test
	@DisplayName("반올림·올림·버림 — 소수 자리, 10원 단위(음수 scale)는 정수 표기")
	void rounding() {
		assertThat(RoundUtils.round(new BigDecimal("1234.5"), 0)).isEqualByComparingTo("1235");
		assertThat(RoundUtils.round(new BigDecimal("2.345"), 2)).isEqualByComparingTo("2.35");
		assertThat(RoundUtils.round(new BigDecimal("1234"), -1).toString()).isEqualTo("1230");
		assertThat(RoundUtils.ceil(new BigDecimal("1231"), -1).toString()).isEqualTo("1240");
		assertThat(RoundUtils.floor(new BigDecimal("1239"), -1).toString()).isEqualTo("1230");
		assertThat(RoundUtils.floor(new BigDecimal("-1.5"), 0)).isEqualByComparingTo("-1");
		assertThat(RoundUtils.round(0.1 + 0.2)).isZero();
	}

	@Test
	@DisplayName("금액 계산 — 수수료 %(버림), 비율, 10원 절사")
	void amount() {
		assertThat(RoundUtils.percentOf(15000, 3.3)).isEqualTo(495);
		assertThat(RoundUtils.percentOf(9999, 3.3)).isEqualTo(329);       // 329.967 → 버림
		assertThat(RoundUtils.rate(1, 3, 1)).isEqualByComparingTo("33.3");
		assertThat(RoundUtils.rate(5, 0, 1)).isEqualByComparingTo("0");
		assertThat(RoundUtils.truncateWon(12345, 10)).isEqualTo(12340);
		assertThat(RoundUtils.truncateWon(12345, 100)).isEqualTo(12300);
		assertThatThrownBy(() -> RoundUtils.truncateWon(1, 0)).isInstanceOf(IllegalArgumentException.class);
	}
}
