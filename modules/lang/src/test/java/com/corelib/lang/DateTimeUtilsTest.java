package com.corelib.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DateTimeUtilsTest {

	@Test
	@DisplayName("여러 형식 해석 — 없는 날짜(2월 30일)와 잘못된 형식은 거부, 빈값은 null")
	void parse() {
		assertThat(DateTimeUtils.parseDate("2026-10-24")).isEqualTo(LocalDate.of(2026, 10, 24));
		assertThat(DateTimeUtils.parseDate("20261024")).isEqualTo(LocalDate.of(2026, 10, 24));
		assertThat(DateTimeUtils.parseDate(" 2026.10.24 ")).isEqualTo(LocalDate.of(2026, 10, 24));
		assertThat(DateTimeUtils.parseDateTime("20261024153000")).isEqualTo(LocalDateTime.of(2026, 10, 24, 15, 30));
		assertThat(DateTimeUtils.parseDateTime("2026-10-24T15:30:00")).isEqualTo(LocalDateTime.of(2026, 10, 24, 15, 30));
		assertThat(DateTimeUtils.parseDate("  ")).isNull();

		assertThatThrownBy(() -> DateTimeUtils.parseDate("2026-02-30")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> DateTimeUtils.parseDate("24/10/2026")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("포맷 — 기본 형식, null 은 null")
	void format() {
		assertThat(DateTimeUtils.format(LocalDateTime.of(2026, 1, 2, 3, 4, 5))).isEqualTo("2026-01-02 03:04:05");
		assertThat(DateTimeUtils.format(LocalDate.of(2026, 1, 2), DateTimeUtils.DATE_COMPACT)).isEqualTo("20260102");
		assertThat(DateTimeUtils.format((LocalDate) null)).isNull();
	}

	@Test
	@DisplayName("범위·계산 — 하루 시작/끝, 월말(윤년), 일수, 만 나이(생일 전날까지 한 살 적게)")
	void calculate() {
		LocalDate date = LocalDate.of(2028, 2, 10);
		assertThat(DateTimeUtils.startOfDay(date)).isEqualTo(LocalDateTime.of(2028, 2, 10, 0, 0));
		assertThat(DateTimeUtils.endOfDay(date).toLocalTime().getHour()).isEqualTo(23);
		assertThat(DateTimeUtils.lastDayOfMonth(date)).isEqualTo(LocalDate.of(2028, 2, 29));
		assertThat(DateTimeUtils.firstDayOfMonth(date)).isEqualTo(LocalDate.of(2028, 2, 1));
		assertThat(DateTimeUtils.daysBetween(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 12, 25))).isEqualTo(62);

		LocalDate birth = LocalDate.of(2000, 10, 25);
		assertThat(DateTimeUtils.age(birth, LocalDate.of(2026, 10, 24))).isEqualTo(25);
		assertThat(DateTimeUtils.age(birth, LocalDate.of(2026, 10, 25))).isEqualTo(26);
		assertThatThrownBy(() -> DateTimeUtils.age(LocalDate.of(2030, 1, 1), LocalDate.of(2026, 1, 1)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("시간대 — UTC 를 한국 시각(+9)으로, epoch 밀리초 왕복")
	void zone() {
		ZonedDateTime utc = ZonedDateTime.of(2026, 10, 24, 15, 0, 0, 0, ZoneOffset.UTC);
		assertThat(DateTimeUtils.toKst(utc)).isEqualTo(LocalDateTime.of(2026, 10, 25, 0, 0));

		LocalDateTime kst = LocalDateTime.of(2026, 10, 24, 9, 0);
		assertThat(DateTimeUtils.fromEpochMilli(DateTimeUtils.toEpochMilli(kst))).isEqualTo(kst);
		assertThat(DateTimeUtils.toEpochMilli(LocalDateTime.of(1970, 1, 1, 9, 0))).isZero();
		assertThat(DateTimeUtils.isBetween(kst, kst, kst.plusDays(1))).isTrue();
	}
}
