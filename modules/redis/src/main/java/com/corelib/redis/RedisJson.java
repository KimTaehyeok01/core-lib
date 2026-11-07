package com.corelib.redis;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import org.springframework.cache.support.NullValue;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

/**
 * Redis 값 JSON 직렬화 — 읽을 때 원래 타입으로 돌아오도록 "@class" 타입 정보를 함께 저장한다.
 * 타입 정보로 아무 클래스나 만들 수 있으면 역직렬화 공격 통로가 되므로, 허용 패키지(java.lang·util·time·math + trusted-packages)만 받는다.
 */
final class RedisJson {

	private static final List<String> DEFAULT_TRUSTED = List.of("java.lang.", "java.util.", "java.time.", "java.math.");

	private RedisJson() {
	}

	/** 타입 정보 없는 매퍼 — RedisUtils.convert 용 */
	static ObjectMapper plainMapper() {
		return new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	}

	static GenericJackson2JsonRedisSerializer serializer(List<String> trustedPackages) {
		ObjectMapper mapper = plainMapper();
		mapper.setDefaultTyping(new TypeResolver(validator(trustedPackages)).init(JsonTypeInfo.Id.CLASS, null).inclusion(JsonTypeInfo.As.PROPERTY));
		GenericJackson2JsonRedisSerializer.registerNullValueSerializer(mapper, null);    // @Cacheable 의 null 결과
		return new GenericJackson2JsonRedisSerializer(mapper);
	}

	static PolymorphicTypeValidator validator(List<String> trustedPackages) {
		BasicPolymorphicTypeValidator.Builder builder = BasicPolymorphicTypeValidator.builder()
			.allowIfSubType(NullValue.class)
			.allowIfSubTypeIsArray();
		DEFAULT_TRUSTED.forEach(builder::allowIfSubType);
		trustedPackages.forEach(trusted -> builder.allowIfSubType(trusted + "."));
		return builder.build();
	}

	/** final 클래스(record 등)까지 타입 정보를 붙인다 — NON_FINAL 이면 record 가 Map 으로 돌아와 @Cacheable 반환에서 ClassCastException */
	private static final class TypeResolver extends ObjectMapper.DefaultTypeResolverBuilder {

		private static final long serialVersionUID = 1L;

		TypeResolver(PolymorphicTypeValidator validator) {
			super(ObjectMapper.DefaultTyping.NON_FINAL, validator);
		}

		@Override
		public boolean useForType(JavaType type) {
			return !type.isPrimitive() && !TreeNode.class.isAssignableFrom(type.getRawClass());
		}
	}
}
