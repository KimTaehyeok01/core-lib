package com.corelib.redis;

/**
 * {@link RedisLock} 의 상호 배제 범위.
 * <ul>
 *   <li>{@link #REQUEST} — 같은 엔드포인트 안에서의 중복 요청 차단. 키 형식: {@code {prefix}:lock:{pattern}:{key}}</li>
 *   <li>{@link #GLOBAL}  — 진입점(엔드포인트·@Async·스케줄러) 무관 자원 배제. 키 형식: {@code {prefix}:lock:global:{key}}</li>
 * </ul>
 */
public enum LockScope {

	/**
	 * 같은 엔드포인트 안에서의 중복 요청 차단 — {@code {prefix}:lock:{pattern}:{key}}.
	 * <p>spring-webmvc + jakarta.servlet 이 classpath 에 모두 있어야 실제 URI 패턴이 들어간다.
	 * 없으면 <code>"uri"</code> fallback 으로 모든 REQUEST scope 호출이 같은 네임스페이스로 수렴 — 이 경우
	 * {@link #GLOBAL} 을 쓰거나 key 값에 전역 유일성을 포함시켜야 한다.</p>
	 */
	REQUEST,

	/** 진입점 무관 자원 배제 — {@code {prefix}:lock:global:{key}} */
	GLOBAL
}
