package com.corelib.redis;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;

/**
 * {@link RedisLock#waitTimeString()}·{@link RedisLock#leaseTimeString()} 해석기.
 * <ul>
 *   <li>{@code "${...}"} placeholder 는 {@link Environment#resolvePlaceholders(String)} 로 해석</li>
 *   <li>단위 없는 숫자 = 초 (예: {@code "3"})</li>
 *   <li>SI 접미사: {@code ns·us·ms·s·m·h·d} (예: {@code "500ms"})</li>
 *   <li>ISO-8601: {@code Duration.parse} (예: {@code "PT1.5S"})</li>
 *   <li>blank 입력은 {@code null} 반환(어노테이션 미지정 의미). 형식 불일치·placeholder 미정의는 CoreException</li>
 * </ul>
 */
final class DurationStringParser {

	private static final Pattern INTEGER = Pattern.compile("^-?\\d+$");
	private static final Pattern SI = Pattern.compile("^(-?\\d+(?:\\.\\d+)?)(ns|us|ms|s|m|h|d)$");
	private static final Pattern PLACEHOLDER_LEFT = Pattern.compile("\\$\\{");

	private DurationStringParser() {
	}

	/**
	 * raw 문자열을 Duration 으로 해석한다. 호출자가 음수·0 허용 여부 등의 의미 검증을 추가로 수행해야 한다.
	 *
	 * @param raw         어노테이션 속성 값. blank 면 null 반환
	 * @param environment placeholder 해석용 환경
	 * @param context     에러 메시지에 사용할 속성 이름(waitTimeString·leaseTimeString 등)
	 * @return Duration (blank 면 null)
	 */
	static Duration parse(String raw, Environment environment, String context) {
		if (StringUtils.isBlank(raw)) {
			return null;
		}
		String resolved = environment == null ? raw : environment.resolvePlaceholders(raw);
		if (PLACEHOLDER_LEFT.matcher(resolved).find()) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
				context + " 의 placeholder 를 해석할 수 없습니다 — " + raw);
		}
		if (StringUtils.isBlank(resolved)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
				context + " 해석 결과가 공백입니다 — " + raw);
		}
		String trimmed = resolved.trim();

		if (INTEGER.matcher(trimmed).matches()) {
			try {
				return Duration.ofSeconds(Long.parseLong(trimmed));
			} catch (NumberFormatException e) {
				throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
					context + " 숫자가 너무 큽니다 — " + raw, e);
			}
		}

		Matcher si = SI.matcher(trimmed);
		if (si.matches()) {
			return fromSi(si.group(1), si.group(2), context, raw);
		}

		if (trimmed.startsWith("PT") || trimmed.startsWith("pt") || trimmed.startsWith("-PT") || trimmed.startsWith("-pt")) {
			try {
				return Duration.parse(trimmed);
			} catch (RuntimeException e) {
				throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
					context + " ISO-8601 형식 해석 실패 — " + raw, e);
			}
		}

		throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
			"알 수 없는 duration 형식: " + raw + " — 숫자(초)·500ms·PT75S 를 사용하세요.");
	}

	/**
	 * {@link RedisLock#waitTimeString()} 해석 — blank 는 null(미지정), 음수는 거부. 0 은 "기다리지 않음" 으로 허용.
	 * @return Duration (blank 면 null), 음수면 CoreException
	 */
	static Duration resolveWait(String raw, Environment environment, String context) {
		Duration parsed = parse(raw, environment, context);
		if (parsed == null) {
			return null;
		}
		if (parsed.isNegative()) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
				context + " 은 0 이상이어야 합니다 — " + raw);
		}
		return parsed;
	}

	/**
	 * {@link RedisLock#leaseTimeString()} 해석 — Duration 직접 비교 (toMillis() 를 쓰면 nano 단위 차이가 ms=0 으로 접혀 sub-ms 미검출).
	 * <ul>
	 *   <li>blank → null (미지정)</li>
	 *   <li>{@code Duration.ZERO} 또는 {@code Duration.ofMillis(-1)} → 워치독 의도. {@code Duration.ofMillis(-1)} 반환</li>
	 *   <li>그 외 음수 → CoreException ("-1ms 또는 0 만 허용"). 단위 없는 {@code -1} 은 {@code -1s} 로 해석되어 여기서 걸린다</li>
	 *   <li>1ms 미만 양수 → CoreException</li>
	 * </ul>
	 */
	static Duration resolveLease(String raw, Environment environment, String context) {
		Duration parsed = parse(raw, environment, context);
		if (parsed == null) {
			return null;
		}
		if (parsed.isZero() || parsed.equals(Duration.ofMillis(-1))) {
			return Duration.ofMillis(-1);
		}
		if (parsed.isNegative()) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
				context + " 은 음수 lease 는 -1ms 또는 0 만 허용 (단위 없는 '-1' 은 '-1s' 로 해석됨) — " + raw);
		}
		if (parsed.compareTo(Duration.ofMillis(1)) < 0) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
				context + " 은 1ms 이상이어야 합니다 — " + raw);
		}
		return parsed;
	}

	private static Duration fromSi(String amount, String unit, String context, String raw) {
		BigDecimal value;
		try {
			value = new BigDecimal(amount);
		} catch (NumberFormatException e) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
				context + " 숫자 해석 실패 — " + raw, e);
		}
		return switch (unit) {
			case "ns" -> Duration.ofNanos(value.setScale(0, RoundingMode.DOWN).longValueExact());
			case "us" -> Duration.ofNanos(value.multiply(BigDecimal.valueOf(1_000)).setScale(0, RoundingMode.DOWN).longValueExact());
			case "ms" -> Duration.ofNanos(value.multiply(BigDecimal.valueOf(1_000_000)).setScale(0, RoundingMode.DOWN).longValueExact());
			case "s" -> Duration.ofNanos(value.multiply(BigDecimal.valueOf(1_000_000_000L)).setScale(0, RoundingMode.DOWN).longValueExact());
			case "m" -> Duration.ofSeconds(value.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.DOWN).longValueExact());
			case "h" -> Duration.ofSeconds(value.multiply(BigDecimal.valueOf(3_600)).setScale(0, RoundingMode.DOWN).longValueExact());
			case "d" -> Duration.ofSeconds(value.multiply(BigDecimal.valueOf(86_400)).setScale(0, RoundingMode.DOWN).longValueExact());
			default -> throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
				"알 수 없는 duration 단위: " + unit + " — " + raw);
		};
	}
}
