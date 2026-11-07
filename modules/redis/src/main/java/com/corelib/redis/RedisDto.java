package com.corelib.redis;

/** RedisUtils 반환 타입 */
public final class RedisDto {

	private RedisDto() {
	}

	/** ZSet 원소와 점수 */
	public record ScoredValue(Object value, double score) {
	}
}
