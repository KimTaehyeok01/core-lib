package com.corelib.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.NullValue;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

class RedisJsonTest {

	record Product(Long id, String name, BigDecimal price, LocalDateTime updatedAt, List<String> tags) {
	}

	private final GenericJackson2JsonRedisSerializer trusting = RedisJson.serializer(List.of("com.corelib.redis"));
	private final GenericJackson2JsonRedisSerializer strict = RedisJson.serializer(List.of());

	@Test
	void record_는_원래_타입으로_돌아온다() {
		Product product = new Product(7L, "키보드", new BigDecimal("39000.50"), LocalDateTime.of(2026, 11, 7, 10, 30), List.of("new", "sale"));

		Object restored = trusting.deserialize(trusting.serialize(product));

		assertThat(restored).isEqualTo(product);
	}

	@Test
	void 자바_기본_타입은_허용목록_없이도_읽힌다() {
		// Map.of(...) 는 JDK 내부 ImmutableCollections$MapN 타입이라 DefaultTyping 이 @class 를 부착 → 역직렬화 결과가 원본과 equal 하지 않다.
		// LinkedHashMap 은 평범한 Map 타입으로 JSON object 로 그대로 매핑된다.
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("count", 3L);
		map.put("at", LocalDateTime.of(2026, 1, 1, 0, 0));

		assertThat(strict.deserialize(strict.serialize(map))).isEqualTo(map);
		assertThat(strict.deserialize(strict.serialize("문자"))).isEqualTo("문자");
		assertThat(strict.deserialize(strict.serialize(5L))).isEqualTo(5L);
		assertThat(strict.deserialize(strict.serialize(List.of(1, 2)))).isEqualTo(List.of(1, 2));
	}

	@Test
	void 캐시_null_결과를_다시_읽는다() {
		assertThat(strict.deserialize(strict.serialize(NullValue.INSTANCE))).isInstanceOf(NullValue.class);
	}

	@Test
	void 허용하지_않은_패키지의_타입은_읽지_않는다() {
		byte[] json = trusting.serialize(new Product(1L, "a", BigDecimal.ONE, null, List.of()));

		assertThatThrownBy(() -> strict.deserialize(json)).isInstanceOf(SerializationException.class);
	}

	@Test
	void INCR_로_쓴_숫자_문자열도_읽힌다() {
		assertThat(strict.deserialize("12".getBytes())).isEqualTo(12);
	}
}
