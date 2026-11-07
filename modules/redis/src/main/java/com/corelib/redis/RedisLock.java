package com.corelib.redis;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 분산 락 — 같은 키의 메서드 실행을 여러 인스턴스에 걸쳐 한 번에 하나로 묶는다 (Redisson RLock).
 * <pre>
 * &#64;RedisLock(key = "'coupon:' + #couponId", scope = LockScope.GLOBAL)
 * public IssueResult issue(Long couponId, Long memberId) { ... }
 * </pre>
 * <ul>
 *   <li>락 키는 {@link #scope()} 에 따라 조립된다 — {@link LockScope#REQUEST}=<code>{prefix}:lock:{pattern}:{key}</code>, {@link LockScope#GLOBAL}=<code>{prefix}:lock:global:{key}</code></li>
 *   <li>같은 프록시의 &#64;Transactional 바깥에서 돈다 — 락 획득 → 트랜잭션 → 커밋 → 락 해제</li>
 *   <li>같은 스레드의 재진입은 허용된다</li>
 * </ul>
 * <p><b>REQUEST scope 주의</b> — URI 매칭 패턴을 키에 포함한다. 같은 <code>/orders/{id}/pay</code> 라도
 * <code>{id}</code> 가 다른 자원끼리 상호 배제하려면 {@link #key()} 식에 자원 ID 를 포함해야 한다.
 * 같은 엔드포인트에서 사용자별 자원 배제면 <code>key = "#accountId + ':' + #id"</code> 처럼 합성한다.</p>
 * <p><b>REQUEST scope classpath 요건</b> — spring-webmvc + jakarta.servlet 이 classpath 에 모두 있어야 실제 URI 패턴을 쓴다.
 * 둘 중 하나라도 없으면 (<code>spring-boot-starter-web</code> 안 쓰는 배치·메시지 소비 앱) <code>"uri"</code> fallback 으로
 * 모든 호출이 같은 네임스페이스로 수렴하므로 전역 유일성이 필요하면 {@link LockScope#GLOBAL} 을 쓴다.</p>
 * <p><b>웹 밖 호출</b> (<code>@Async</code>·스케줄러·이벤트 리스너) 에서도 REQUEST scope 를 쓰면 요청 컨텍스트가 없어
 * <code>lock:uri:{key}</code> 단일 네임스페이스로 수렴한다 — key 값이 전역 유일해야 상호 배제가 정상 동작한다.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RedisLock {

	/**
	 * 락 키 SpEL — 메서드 파라미터는 #이름 (또는 #p0). 결과가 비면 400.
	 * <p><b>로그 노출 주의</b> — 평가 결과는 운영 로그({@code lockKey=...}) 와 {@code addErrorLogData} 에 그대로 남는다.
	 * PII(전화번호·이메일·주민번호) 를 그대로 키로 쓰지 말고, 사용자 입력 String 은 사전 정제(trim·길이 제한) 하거나 ID·해시로 바꿔서 전달한다.</p>
	 * <p><b>컴파일 옵션</b> — {@code #이름} 해석은 {@code -parameters} 컴파일 옵션이 필요하다
	 * (<code>options.compilerArgs &lt;&lt; '-parameters'</code>). 없으면 기동 시 즉시 500 으로 거부된다.</p>
	 */
	String key();

	/** 락 범위 — 기본 {@link LockScope#REQUEST}. {@link LockScope#GLOBAL} 은 진입점(다른 엔드포인트·@Async·배치) 무관 자원 배제 */
	LockScope scope() default LockScope.REQUEST;

	/** 락을 기다리는 최대 시간 — 넘기면 409 (failOpen 이면 락 없이 실행). 0 이면 기다리지 않는다 */
	long waitTime() default 3;

	/** 락 보유 상한 — 0 이하(기본)는 메서드가 끝날 때까지 자동 연장(Redisson 워치독). 양수면 그 시간 뒤 자동 해제 */
	long leaseTime() default -1;

	TimeUnit timeUnit() default TimeUnit.SECONDS;

	/**
	 * {@link #waitTime()} 을 문자열로 — 공백이 아니면 {@link #waitTime()}·{@link #timeUnit()} 보다 우선.
	 * <ul>
	 *   <li>{@code "${...}"} placeholder 해석 지원 (예: {@code "${app.lock.wait:1s}"})</li>
	 *   <li>단위 없는 숫자 = 초 (예: {@code "3"})</li>
	 *   <li>SI 접미사 허용: {@code ns·us·ms·s·m·h·d} (예: {@code "500ms"})</li>
	 *   <li>ISO-8601: {@code "PT1.5S"}, {@code "PT75S"}</li>
	 * </ul>
	 */
	String waitTimeString() default "";

	/**
	 * {@link #leaseTime()} 을 문자열로 — 공백이 아니면 {@link #leaseTime()}·{@link #timeUnit()} 보다 우선.
	 * 형식은 {@link #waitTimeString()} 과 동일. 해석 결과가 {@code 0} 또는 {@code -1ms} 면 워치독(long leaseTime 과 동일 규칙).
	 * <p>워치독을 명시하려면 {@code "0"} 또는 {@code "-1ms"} 를 쓴다. 단위 없는 {@code "-1"} 은 {@code "-1s"} 로 해석되어
	 * "음수 lease" 오류로 떨어진다.</p>
	 */
	String leaseTimeString() default "";

	/**
	 * 락을 못 잡았을 때(대기 초과·Redis 장애·Redis 미설정) 락 없이 실행할지.
	 * false(기본) 면 메서드를 실행하지 않고 409/500. true 는 중복 실행돼도 안전한 경로(집계 재계산 등)에만 쓴다.
	 */
	boolean failOpen() default false;
}
