package com.corelib.lang;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 날짜·시간 유틸 — 서버 시간대와 상관없이 한국 시간(Asia/Seoul) 기준으로 계산한다.
 * 형식 문자열은 엄격 모드(STRICT)로 해석해 2월 30일 같은 값은 거부한다.
 */
public final class DateTimeUtils {

	public static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 화면·API 에서 자주 쓰는 형식 */
	public static final DateTimeFormatter DATE = strict("uuuu-MM-dd");
	public static final DateTimeFormatter DATE_COMPACT = strict("uuuuMMdd");
	public static final DateTimeFormatter DATE_TIME = strict("uuuu-MM-dd HH:mm:ss");
	public static final DateTimeFormatter DATE_TIME_COMPACT = strict("uuuuMMddHHmmss");

	private static final List<DateTimeFormatter> DATE_FORMATS = List.of(DATE, DATE_COMPACT, strict("uuuu.MM.dd"), strict("uuuu/MM/dd"));
	private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(DATE_TIME, DATE_TIME_COMPACT,
		strict("uuuu-MM-dd'T'HH:mm:ss"), strict("uuuu-MM-dd HH:mm"), strict("uuuu.MM.dd HH:mm:ss"));

	private DateTimeUtils() {
	}

	/** @return 지금 한국 날짜·시각 */
	public static LocalDateTime now() {
		return LocalDateTime.now(KST);
	}

	/** @return 오늘 한국 날짜 */
	public static LocalDate today() {
		return LocalDate.now(KST);
	}

	/** null 이면 null */
	public static String format(LocalDate date, DateTimeFormatter formatter) {
		return date == null ? null : date.format(formatter);
	}

	/** null 이면 null */
	public static String format(LocalDateTime dateTime, DateTimeFormatter formatter) {
		return dateTime == null ? null : dateTime.format(formatter);
	}

	/** "yyyy-MM-dd" — null 이면 null */
	public static String format(LocalDate date) {
		return format(date, DATE);
	}

	/** "yyyy-MM-dd HH:mm:ss" — null 이면 null */
	public static String format(LocalDateTime dateTime) {
		return format(dateTime, DATE_TIME);
	}

	/**
	 * 날짜 문자열 해석 — yyyy-MM-dd · yyyyMMdd · yyyy.MM.dd · yyyy/MM/dd 를 받는다.
	 * @return 빈값이면 null
	 * @throws IllegalArgumentException 형식이 아니거나 없는 날짜
	 */
	public static LocalDate parseDate(String value) {
		String text = StringUtils.trimToNull(value);
		if (text == null) {
			return null;
		}
		for (DateTimeFormatter formatter : DATE_FORMATS) {
			try {
				return LocalDate.parse(text, formatter);
			} catch (DateTimeParseException ignored) {
				// 다음 형식 시도
			}
		}
		throw new IllegalArgumentException("날짜 형식이 아닙니다: " + StringUtils.truncate(text, 30));
	}

	/**
	 * 날짜·시각 문자열 해석 — yyyy-MM-dd HH:mm:ss · yyyyMMddHHmmss · ISO(T) · yyyy-MM-dd HH:mm · yyyy.MM.dd HH:mm:ss.
	 * @return 빈값이면 null
	 * @throws IllegalArgumentException 형식이 아니거나 없는 날짜·시각
	 */
	public static LocalDateTime parseDateTime(String value) {
		String text = StringUtils.trimToNull(value);
		if (text == null) {
			return null;
		}
		for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
			try {
				return LocalDateTime.parse(text, formatter);
			} catch (DateTimeParseException ignored) {
				// 다음 형식 시도
			}
		}
		throw new IllegalArgumentException("날짜·시각 형식이 아닙니다: " + StringUtils.truncate(text, 30));
	}

	/** @return 그날 00:00:00 */
	public static LocalDateTime startOfDay(LocalDate date) {
		return date.atStartOfDay();
	}

	/** @return 그날 23:59:59.999999999 — DB 조회는 다음날 00:00 미만(&lt;)으로 쓰는 편이 안전하다 */
	public static LocalDateTime endOfDay(LocalDate date) {
		return date.atTime(LocalTime.MAX);
	}

	/** @return 그 달 1일 */
	public static LocalDate firstDayOfMonth(LocalDate date) {
		return date.with(TemporalAdjusters.firstDayOfMonth());
	}

	/** @return 그 달 마지막 날 (윤년 반영) */
	public static LocalDate lastDayOfMonth(LocalDate date) {
		return date.with(TemporalAdjusters.lastDayOfMonth());
	}

	/** @return to - from 일수 (from 이 늦으면 음수) */
	public static long daysBetween(LocalDate from, LocalDate to) {
		return ChronoUnit.DAYS.between(from, to);
	}

	/** @return 오늘(한국) 기준 만 나이 */
	public static int age(LocalDate birthDate) {
		return age(birthDate, today());
	}

	/** @return baseDate 기준 만 나이 */
	public static int age(LocalDate birthDate, LocalDate baseDate) {
		if (birthDate.isAfter(baseDate)) {
			throw new IllegalArgumentException("생년월일이 기준일보다 늦습니다.");
		}
		return Period.between(birthDate, baseDate).getYears();
	}

	/** @return start ≤ target ≤ end 면 true (양 끝 포함) */
	public static boolean isBetween(LocalDateTime target, LocalDateTime start, LocalDateTime end) {
		return !target.isBefore(start) && !target.isAfter(end);
	}

	/** epoch 밀리초(외부 API·JWT 등) → 한국 시각 */
	public static LocalDateTime fromEpochMilli(long epochMilli) {
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), KST);
	}

	/** 한국 시각 → epoch 밀리초 */
	public static long toEpochMilli(LocalDateTime dateTime) {
		return dateTime.atZone(KST).toInstant().toEpochMilli();
	}

	/** 다른 시간대 시각(예: UTC 응답) → 한국 시각 */
	public static LocalDateTime toKst(ZonedDateTime dateTime) {
		return dateTime.withZoneSameInstant(KST).toLocalDateTime();
	}

	private static DateTimeFormatter strict(String pattern) {
		return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
	}
}
