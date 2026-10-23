package com.corelib.lang;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 금액·비율 반올림 — double 대신 BigDecimal 로 계산해 0.1 + 0.2 같은 오차를 피한다.
 * <ul>
 *   <li>scale: 소수 자릿수 (0 = 정수, 음수 = 10원·100원 단위)</li>
 *   <li>할인·수수료 같은 금액은 보통 {@link #floor}(버림) — 고객에게 불리하지 않게</li>
 * </ul>
 */
public final class RoundUtils {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private RoundUtils() {
	}

	/** 반올림 (HALF_UP) — round(1234.5, 0) = 1235, round(1234, -1) = 1230 */
	public static BigDecimal round(BigDecimal value, int scale) {
		return plain(value.setScale(scale, RoundingMode.HALF_UP));
	}

	/** 올림 (0 에서 멀어지는 방향) — ceil(1231, -1) = 1240 */
	public static BigDecimal ceil(BigDecimal value, int scale) {
		return plain(value.setScale(scale, RoundingMode.UP));
	}

	/** 버림 (0 쪽으로) — floor(1239, -1) = 1230, floor(-1.5, 0) = -1 */
	public static BigDecimal floor(BigDecimal value, int scale) {
		return plain(value.setScale(scale, RoundingMode.DOWN));
	}

	/** 원 단위 금액 반올림 — round(1234.5) = 1235 */
	public static long round(double value) {
		return round(BigDecimal.valueOf(value), 0).longValueExact();
	}

	/** 금액의 rate% (버림) — percentOf(15000, 3.3) = 495 (15000 × 3.3% = 495) */
	public static long percentOf(long amount, double rate) {
		return floor(BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(rate)).divide(HUNDRED), 0).longValueExact();
	}

	/** part 가 total 의 몇 % 인지 (scale 자리 반올림) — rate(1, 3, 1) = 33.3, total 이 0 이면 0 */
	public static BigDecimal rate(long part, long total, int scale) {
		if (total == 0) {
			return BigDecimal.ZERO.setScale(Math.max(scale, 0));
		}
		return BigDecimal.valueOf(part).multiply(HUNDRED).divide(BigDecimal.valueOf(total), scale, RoundingMode.HALF_UP);
	}

	/** 음수 scale 결과(1.23E+3)를 정수 표기(1230)로 — JSON·화면에 지수 표기가 나가지 않게 */
	private static BigDecimal plain(BigDecimal value) {
		return value.scale() < 0 ? value.setScale(0) : value;
	}

	/** 10원 단위 절사 — truncateWon(12345, 10) = 12340 */
	public static long truncateWon(long amount, long unit) {
		if (unit <= 0) {
			throw new IllegalArgumentException("unit 은 1 이상이어야 합니다.");
		}
		return amount - (amount % unit);
	}
}
