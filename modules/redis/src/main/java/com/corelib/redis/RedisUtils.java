package com.corelib.redis;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * Redis 자료구조 static 진입점 — 키에는 core.redis.key-prefix 가 자동으로 붙는다.
 * <ul>
 *   <li>{@link Strings} 값·카운터 / {@link Sets} 집합 / {@link Lists} 목록 / {@link Hashes} 해시 / {@link ZSets} 정렬 집합(랭킹)</li>
 *   <li>값은 JSON 으로 저장된다 — 직접 만든 DTO 는 core.redis.trusted-packages 에 패키지를 넣어야 다시 읽힌다</li>
 *   <li>Redis 장애는 Spring Data 예외(RedisConnectionFailureException 등)가 그대로 올라간다</li>
 *   <li>ttl 을 받는 쓰기는 쓰기와 만료 설정이 두 명령이다 — 원자성이 필요하면 {@link Strings#set(String, Object, Duration)} 처럼 한 명령짜리를 쓴다</li>
 * </ul>
 */
public final class RedisUtils {

	private static volatile State state;

	private RedisUtils() {
	}

	/** 기동 시 {@link RedisInitialize} 가 1회 호출한다 */
	static void initialize(RedisTemplate<String, Object> template, RedisProperty property, ObjectMapper objectMapper) {
		state = new State(template, property, objectMapper);
	}

	static boolean isInitialized() {
		return state != null;
	}

	// ===== 키 공통 =====

	public static boolean exists(String key) {
		return Boolean.TRUE.equals(template().hasKey(key(key)));
	}

	public static boolean delete(String key) {
		return Boolean.TRUE.equals(template().delete(key(key)));
	}

	/** @return 만료가 설정됐으면 true (키가 없으면 false) */
	public static boolean expire(String key, Duration ttl) {
		return Boolean.TRUE.equals(template().expire(key(key), positive(ttl)));
	}

	/** @return 남은 시간(초) — 만료 없음 -1, 키 없음 -2 */
	public static long getExpire(String key) {
		Long seconds = template().getExpire(key(key));
		return seconds == null ? -2 : seconds;
	}

	/**
	 * 패턴에 맞는 키 (prefix 를 뗀 이름). KEYS 대신 SCAN 으로 훑어 Redis 를 막지 않는다.
	 * @param pattern glob 패턴 (예: "cart:*") — prefix 는 자동으로 붙는다
	 * @param limit   최대 개수 — 키가 많은 운영 Redis 에서 전체를 끌어오지 않게
	 */
	public static List<String> keys(String pattern, int limit) {
		requireText(pattern, "pattern");
		if (limit < 1) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "limit 은 1 이상이어야 합니다.");
		}
		State current = state();
		String prefix = current.property.applyPrefix("");
		List<String> result = new ArrayList<>();
		ScanOptions options = ScanOptions.scanOptions().match(current.property.applyPrefix(pattern)).count(500).build();
		try (Cursor<String> cursor = current.template.scan(options)) {
			while (cursor.hasNext() && result.size() < limit) {
				result.add(cursor.next().substring(prefix.length()));
			}
		}
		return result;
	}

	/** 값을 원하는 타입으로 — 숫자(Integer↔Long)·Map → DTO 차이를 맞춘다. null 은 null */
	public static <T> T convert(Object value, Class<T> type) {
		return value == null ? null : state().objectMapper.convertValue(value, type);
	}

	// ===== String (값·카운터) =====

	public static final class Strings {

		private Strings() {
		}

		public static Object get(String key) {
			return template().opsForValue().get(key(key));
		}

		public static <T> T get(String key, Class<T> type) {
			return convert(get(key), type);
		}

		/**
		 * ⚠️ 카운터 값은 {@link #setLong(String, long)} 또는 {@link #increment(String)} 를 쓴다 —
		 * {@code set(Object)} 로 저장한 Long·Integer 는 JSON 타입 정보가 붙어 (예: {@code ["java.lang.Long",3]}) 이후 INCR/DECR 가 실패한다.
		 */
		public static void set(String key, Object value) {
			template().opsForValue().set(key(key), value(value));
		}

		/** 값과 만료를 한 명령(SET EX)으로 */
		public static void set(String key, Object value, Duration ttl) {
			template().opsForValue().set(key(key), value(value), positive(ttl));
		}

		/** INCR/DECR 대상 카운터 값 저장 — raw 숫자 문자열. String 직렬화 경로를 사용해 JSON 타입 정보가 붙지 않는다 */
		public static void setLong(String key, long value) {
			state().template.execute((org.springframework.data.redis.core.RedisCallback<Void>) conn -> {
				conn.stringCommands().set(key(key).getBytes(java.nio.charset.StandardCharsets.UTF_8),
					Long.toString(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
				return null;
			});
		}

		/** {@link #setLong(String, long)} + TTL 을 한 명령(SET EX)으로 */
		public static void setLong(String key, long value, Duration ttl) {
			positive(ttl);
			state().template.execute((org.springframework.data.redis.core.RedisCallback<Void>) conn -> {
				conn.stringCommands().set(key(key).getBytes(java.nio.charset.StandardCharsets.UTF_8),
					Long.toString(value).getBytes(java.nio.charset.StandardCharsets.UTF_8),
					org.springframework.data.redis.core.types.Expiration.from(ttl),
					org.springframework.data.redis.connection.RedisStringCommands.SetOption.UPSERT);
				return null;
			});
		}

		/** 키가 없을 때만 저장 (SET NX EX) — 중복 요청 차단·멱등 키에. @return 저장했으면 true */
		public static boolean setIfAbsent(String key, Object value, Duration ttl) {
			return Boolean.TRUE.equals(template().opsForValue().setIfAbsent(key(key), value(value), positive(ttl)));
		}

		/** @return 증가 후 값 (키가 없으면 0 에서 시작) */
		public static long increment(String key, long delta) {
			return Objects.requireNonNull(template().opsForValue().increment(key(key), delta));
		}

		public static long increment(String key) {
			return increment(key, 1);
		}

		public static long decrement(String key, long delta) {
			return Objects.requireNonNull(template().opsForValue().decrement(key(key), delta));
		}

		public static long decrement(String key) {
			return decrement(key, 1);
		}
	}

	// ===== Set =====

	public static final class Sets {

		private Sets() {
		}

		public static Set<Object> members(String key) {
			Set<Object> members = template().opsForSet().members(key(key));
			return members == null ? Set.of() : members;
		}

		public static long size(String key) {
			return orZero(template().opsForSet().size(key(key)));
		}

		public static boolean contains(String key, Object value) {
			return Boolean.TRUE.equals(template().opsForSet().isMember(key(key), value(value)));
		}

		/** @return 새로 들어간 개수 (이미 있던 값은 0) */
		public static long add(String key, Object... values) {
			return orZero(template().opsForSet().add(key(key), values(values)));
		}

		public static long add(String key, Duration ttl, Object... values) {
			long added = add(key, values);
			expire(key, ttl);
			return added;
		}

		/** @return 실제로 지운 개수 */
		public static long remove(String key, Object... values) {
			return orZero(template().opsForSet().remove(key(key), values(values)));
		}
	}

	// ===== List =====

	public static final class Lists {

		private Lists() {
		}

		/** start~end (0 부터, 끝 포함). -1 은 마지막 */
		public static List<Object> range(String key, long start, long end) {
			List<Object> values = template().opsForList().range(key(key), start, end);
			return values == null ? List.of() : values;
		}

		public static List<Object> all(String key) {
			return range(key, 0, -1);
		}

		public static long size(String key) {
			return orZero(template().opsForList().size(key(key)));
		}

		/** 맨 앞에 추가. @return 추가 후 길이 */
		public static long pushFirst(String key, Object value) {
			return orZero(template().opsForList().leftPush(key(key), value(value)));
		}

		/** 맨 뒤에 추가. @return 추가 후 길이 */
		public static long pushLast(String key, Object value) {
			return orZero(template().opsForList().rightPush(key(key), value(value)));
		}

		/** 맨 앞을 꺼낸다 — 비었으면 null */
		public static Object popFirst(String key) {
			return template().opsForList().leftPop(key(key));
		}

		/** 맨 뒤를 꺼낸다 — 비었으면 null */
		public static Object popLast(String key) {
			return template().opsForList().rightPop(key(key));
		}

		/** start~end 만 남기고 자른다 — "최근 N개" 유지 */
		public static void trim(String key, long start, long end) {
			template().opsForList().trim(key(key), start, end);
		}

		/** 같은 값을 모두 지운다. @return 지운 개수 */
		public static long remove(String key, Object value) {
			return orZero(template().opsForList().remove(key(key), 0, value(value)));
		}
	}

	// ===== Hash =====

	public static final class Hashes {

		private Hashes() {
		}

		public static Map<String, Object> entries(String key) {
			Map<String, Object> entries = new LinkedHashMap<>();
			template().opsForHash().entries(key(key)).forEach((field, value) -> entries.put(String.valueOf(field), value));
			return entries;
		}

		public static Object get(String key, String field) {
			return template().opsForHash().get(key(key), requireText(field, "field"));
		}

		public static <T> T get(String key, String field, Class<T> type) {
			return convert(get(key, field), type);
		}

		/** fields 순서대로 — 없는 필드는 null */
		public static List<Object> multiGet(String key, Collection<String> fields) {
			return template().opsForHash().multiGet(key(key), new ArrayList<>(fields));
		}

		public static boolean hasField(String key, String field) {
			return template().opsForHash().hasKey(key(key), requireText(field, "field"));
		}

		public static long size(String key) {
			return template().opsForHash().size(key(key));
		}

		public static void put(String key, String field, Object value) {
			template().opsForHash().put(key(key), requireText(field, "field"), value(value));
		}

		public static void putAll(String key, Map<String, ?> values) {
			template().opsForHash().putAll(key(key), values);
		}

		public static void putAll(String key, Map<String, ?> values, Duration ttl) {
			putAll(key, values);
			expire(key, ttl);
		}

		/** @return 숫자 필드 증가 후 값 */
		public static long increment(String key, String field, long delta) {
			return template().opsForHash().increment(key(key), requireText(field, "field"), delta);
		}

		/** @return 실제로 지운 개수 */
		public static long delete(String key, String... fields) {
			return template().opsForHash().delete(key(key), (Object[]) fields);
		}
	}

	// ===== Sorted Set (랭킹) =====

	public static final class ZSets {

		private ZSets() {
		}

		/** 점수를 덮어쓴다 (없으면 추가) */
		public static void add(String key, Object value, double score) {
			template().opsForZSet().add(key(key), value(value), score);
		}

		/** 점수를 더한다 (없으면 delta 로 추가) — 조회수·검색 횟수. @return 더한 뒤 점수 */
		public static double incrementScore(String key, Object value, double delta) {
			return Objects.requireNonNull(template().opsForZSet().incrementScore(key(key), value(value), delta));
		}

		public static Double score(String key, Object value) {
			return template().opsForZSet().score(key(key), value(value));
		}

		/** @param highFirst true 면 점수 높은 순 (0 이 1등). 없으면 null */
		public static Long rank(String key, Object value, boolean highFirst) {
			ZSetOperations<String, Object> ops = template().opsForZSet();
			return highFirst ? ops.reverseRank(key(key), value(value)) : ops.rank(key(key), value(value));
		}

		public static long size(String key) {
			return orZero(template().opsForZSet().zCard(key(key)));
		}

		/** 순위 start~end (0 부터, 끝 포함) 의 값과 점수 */
		public static List<RedisDto.ScoredValue> range(String key, long start, long end, boolean highFirst) {
			ZSetOperations<String, Object> ops = template().opsForZSet();
			return scored(highFirst ? ops.reverseRangeWithScores(key(key), start, end) : ops.rangeWithScores(key(key), start, end));
		}

		/** 상위 N 개 (점수 높은 순) */
		public static List<RedisDto.ScoredValue> top(String key, int count) {
			return count < 1 ? List.of() : range(key, 0, count - 1L, true);
		}

		/** 점수 min~max (양끝 포함) 의 값과 점수 */
		public static List<RedisDto.ScoredValue> rangeByScore(String key, double min, double max, boolean highFirst) {
			ZSetOperations<String, Object> ops = template().opsForZSet();
			return scored(highFirst ? ops.reverseRangeByScoreWithScores(key(key), min, max) : ops.rangeByScoreWithScores(key(key), min, max));
		}

		/** @return 실제로 지운 개수 */
		public static long remove(String key, Object... values) {
			return orZero(template().opsForZSet().remove(key(key), values(values)));
		}

		/** 낮은 점수 순 순위 start~end 를 지운다 — 상위 N 개만 남길 때 removeRange(key, 0, -(N+1)) */
		public static long removeRange(String key, long start, long end) {
			return orZero(template().opsForZSet().removeRange(key(key), start, end));
		}

		private static List<RedisDto.ScoredValue> scored(Set<ZSetOperations.TypedTuple<Object>> tuples) {
			if (tuples == null) {
				return List.of();
			}
			List<RedisDto.ScoredValue> result = new ArrayList<>(tuples.size());
			for (ZSetOperations.TypedTuple<Object> tuple : new LinkedHashSet<>(tuples)) {
				result.add(new RedisDto.ScoredValue(tuple.getValue(), tuple.getScore() == null ? 0 : tuple.getScore()));
			}
			return result;
		}
	}

	// ===== 내부 =====

	private static State state() {
		State current = state;
		if (current == null) {
			throw new CoreException(RedisError.UNAVAILABLE, "RedisUtils 미초기화 - spring.data.redis.host·url·sentinel.nodes·cluster.nodes 설정 확인");
		}
		return current;
	}

	private static RedisTemplate<String, Object> template() {
		return state().template;
	}

	private static String key(String key) {
		return state().property.applyPrefix(requireText(key, "key"));
	}

	private static String requireText(String value, String name) {
		if (StringUtils.isBlank(value)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "Redis " + name + " 가 비어 있습니다.");
		}
		return value;
	}

	private static Object value(Object value) {
		if (value == null) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "Redis 에 null 값은 저장할 수 없습니다.");
		}
		return value;
	}

	private static Object[] values(Object[] values) {
		if (values == null || values.length == 0) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "Redis 값이 비어 있습니다.");
		}
		for (Object value : values) {
			value(value);
		}
		return values;
	}

	private static Duration positive(Duration ttl) {
		if (ttl == null || ttl.isNegative() || ttl.isZero()) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "Redis ttl 은 0 보다 커야 합니다.");
		}
		return ttl;
	}

	private static long orZero(Long value) {
		return value == null ? 0 : value;
	}

	private record State(RedisTemplate<String, Object> template, RedisProperty property, ObjectMapper objectMapper) {
	}
}
