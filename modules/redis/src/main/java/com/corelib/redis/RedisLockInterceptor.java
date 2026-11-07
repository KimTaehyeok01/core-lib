package com.corelib.redis;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.ClassUtils;

/**
 * {@link RedisLock} 처리 — 락 획득 → 메서드 → 해제.
 * <ul>
 *   <li>대기 초과: 409 (failOpen 이면 락 없이 진행) / 대기 중 인터럽트: 항상 409</li>
 *   <li>Redis 장애·미설정: 500 (failOpen 이면 락 없이 진행)</li>
 *   <li>장기 보유 감시: {@code core.redis.lock.long-hold-warn-threshold} 를 넘기면 warn — 락을 강제로 풀지 않는다</li>
 * </ul>
 * lockKey 는 로그용 데이터로만 싣고 응답에는 내보내지 않는다. 제어문자·초과 길이는 {@link #safe(String)} 로 정제한다.
 */
@Slf4j
class RedisLockInterceptor implements MethodInterceptor, DisposableBean {

	private static final ExpressionParser PARSER = new SpelExpressionParser();
	private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();
	/** spring-webmvc + jakarta.servlet 이 classpath 에 모두 있을 때만 REQUEST scope 가 실제 URI 패턴을 쓴다 — 없으면 "uri" fallback */
	private static final boolean WEB_PRESENT =
		ClassUtils.isPresent("org.springframework.web.servlet.HandlerMapping", RedisLockInterceptor.class.getClassLoader())
			&& ClassUtils.isPresent("jakarta.servlet.http.HttpServletRequest", RedisLockInterceptor.class.getClassLoader());

	private final ObjectProvider<RedissonClient> redissonClient;
	private final ObjectProvider<RedisProperty> property;
	private final Environment environment;
	private final Map<Method, Expression> expressions = new ConcurrentHashMap<>();
	private final Map<Method, ResolvedTimes> timesCache = new ConcurrentHashMap<>();
	private final ScheduledExecutorService longHoldWatcher = newScheduler();

	RedisLockInterceptor(ObjectProvider<RedissonClient> redissonClient, ObjectProvider<RedisProperty> property, Environment environment) {
		this.redissonClient = redissonClient;
		this.property = property;
		this.environment = environment;
	}

	/** removeOnCancelPolicy=true — 취소된 task 를 큐에서 즉시 제거해 1 코어 스케줄러가 느리게 끓지 않게 */
	private static ScheduledExecutorService newScheduler() {
		ScheduledThreadPoolExecutor stpe = new ScheduledThreadPoolExecutor(1, r -> {
			Thread thread = new Thread(r, "redis-lock-hold-watch");
			thread.setDaemon(true);
			return thread;
		});
		stpe.setRemoveOnCancelPolicy(true);
		return stpe;
	}

	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Method method = invocation.getMethod();
		Class<?> targetClass = invocation.getThis() == null ? method.getDeclaringClass() : AopUtils.getTargetClass(invocation.getThis());
		Method specific = AopUtils.getMostSpecificMethod(method, targetClass);
		RedisLock annotation = AnnotatedElementUtils.findMergedAnnotation(specific, RedisLock.class);
		if (annotation == null) {
			return invocation.proceed();
		}
		String lockKey = lockKey(annotation, specific, invocation);
		ResolvedTimes times = timesCache.computeIfAbsent(specific, m -> resolveTimes(annotation, m));

		RedissonClient client = redissonClient.getIfUnique();
		if (client == null) {
			return failOpenOrThrow(annotation, invocation, lockKey,
				new CoreException(RedisError.UNAVAILABLE, "@RedisLock 실행 불가 - Redis 미설정(spring.data.redis.*) 또는 RedissonClient 가 둘 이상(@Primary 필요)"));
		}

		RLock lock = client.getLock(lockKey);
		boolean acquired;
		try {
			acquired = lock.tryLock(times.waitMillis(), times.leaseMillis(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CoreException(RedisError.LOCK_CONFLICT, "락 대기 중 인터럽트", e).addErrorLogData("lockKey", lockKey);
		} catch (RuntimeException e) {
			return failOpenOrThrow(annotation, invocation, lockKey, new CoreException(RedisError.UNAVAILABLE, "락 획득 실패 - Redis 연결 확인", e));
		}
		if (!acquired) {
			return failOpenOrThrow(annotation, invocation, lockKey, new CoreException(RedisError.LOCK_CONFLICT, "대기 시간 안에 락을 잡지 못함"));
		}

		Duration threshold = longHoldWarnThreshold();
		try {
			LongHoldGuard guard = LongHoldGuard.start(longHoldWatcher, lockKey, threshold, times.leaseMillis());
			try {
				return invocation.proceed();
			} finally {
				if (guard != null) {
					guard.cancel();
				}
			}
		} finally {
			unlock(lock, lockKey);
		}
	}

	@Override
	public void destroy() {
		longHoldWatcher.shutdownNow();
	}

	private Object failOpenOrThrow(RedisLock annotation, MethodInvocation invocation, String lockKey, CoreException cause) throws Throwable {
		if (!annotation.failOpen()) {
			throw cause.addErrorLogData("lockKey", lockKey);
		}
		log.warn("락 없이 진행 (failOpen). lockKey={}, reason={}", safe(lockKey), cause.getMessage());
		return invocation.proceed();
	}

	/** 유한 leaseTime 이 처리보다 먼저 끝났으면 이미 풀려 있다 — 처리 시간보다 넉넉히 잡으라는 신호로 warn */
	private static void unlock(RLock lock, String lockKey) {
		try {
			lock.unlock();
		} catch (IllegalMonitorStateException e) {
			log.warn("Lock not held at unlock (watchdog mode) - leaseTime 이 처리 시간보다 짧거나 워치독 만료. lockKey={}", safe(lockKey));
		} catch (RuntimeException e) {
			log.warn("락 해제 실패 - Redis 연결 확인 (워치독이 멈추면 최대 30초 뒤 만료). lockKey={}", safe(lockKey), e);
		}
	}

	private String lockKey(RedisLock annotation, Method method, MethodInvocation invocation) {
		// '-parameters' 컴파일 옵션이 꺼져 있으면 SpEL '#id' 가 조용히 null 로 평가되어 서로 다른 자원이 같은 락으로 수렴한다 → 즉시 실패
		if (method.getParameters().length > 0 && PARAMETER_NAMES.getParameterNames(method) == null) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
				"메서드 파라미터 이름이 유실됨 — build.gradle 에 '-parameters' 컴파일 옵션 추가 필요. 대상: " + name(method));
		}
		Expression expression = expressions.computeIfAbsent(method, ignored -> parse(annotation.key(), method));
		Object value;
		try {
			value = expression.getValue(new MethodBasedEvaluationContext(invocation.getThis(), method, invocation.getArguments(), PARAMETER_NAMES));
		} catch (RuntimeException e) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "@RedisLock key 평가 실패 - " + name(method), e);
		}
		String keyValue = value == null ? null : String.valueOf(value);
		if (StringUtils.isBlank(keyValue)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "락 키가 비어 있습니다.");
		}
		String composed = annotation.scope() == LockScope.GLOBAL
			? "lock:global:" + keyValue
			: "lock:" + requestPattern() + ":" + keyValue;
		return property.getIfAvailable(RedisProperty::new).applyPrefix(composed);
	}

	/** REQUEST scope 용 URI 패턴 — spring-web 없으면 "uri" fallback (모든 호출이 같은 네임스페이스로 수렴 — key 식으로 전역 유일화해야) */
	private static String requestPattern() {
		if (!WEB_PRESENT) {
			return "uri";
		}
		return ServletRequestInspector.currentPattern();
	}

	private ResolvedTimes resolveTimes(RedisLock annotation, Method method) {
		long waitMillis;
		if (StringUtils.isNotBlank(annotation.waitTimeString())) {
			Duration parsed = DurationStringParser.resolveWait(annotation.waitTimeString(), environment, "waitTimeString");
			if (parsed == null) {
				throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
					"@RedisLock waitTimeString 해석 실패 — " + name(method) + ": " + annotation.waitTimeString());
			}
			waitMillis = parsed.toMillis();
		} else {
			waitMillis = annotation.timeUnit().toMillis(annotation.waitTime());
			if (waitMillis < 0) {
				waitMillis = 0;
			}
		}

		long leaseMillis;
		if (StringUtils.isNotBlank(annotation.leaseTimeString())) {
			Duration parsed = DurationStringParser.resolveLease(annotation.leaseTimeString(), environment, "leaseTimeString");
			if (parsed == null) {
				throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
					"@RedisLock leaseTimeString 해석 실패 — " + name(method) + ": " + annotation.leaseTimeString());
			}
			// resolveLease 는 워치독 의도를 Duration.ofMillis(-1) 로 정규화해 반환한다
			leaseMillis = parsed.equals(Duration.ofMillis(-1)) ? -1 : parsed.toMillis();
		} else {
			long raw = annotation.leaseTime();
			leaseMillis = raw > 0 ? annotation.timeUnit().toMillis(raw) : -1;
		}

		return new ResolvedTimes(waitMillis, leaseMillis);
	}

	private Duration longHoldWarnThreshold() {
		RedisProperty value = property.getIfAvailable();
		if (value == null || value.getLock() == null) {
			return null;
		}
		Duration threshold = value.getLock().getLongHoldWarnThreshold();
		return threshold == null || threshold.isZero() ? null : threshold;
	}

	private static Expression parse(String key, Method method) {
		try {
			return PARSER.parseExpression(key);
		} catch (RuntimeException e) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "@RedisLock key 식 오류 - " + name(method), e);
		}
	}

	private static String name(Method method) {
		return method.getDeclaringClass().getSimpleName() + "." + method.getName();
	}

	/** 로그용 lockKey 정제 — 제어문자(\n·\r·\t·NUL·\u001f·\u007f 등)는 '_' 로, 200자 초과분은 "..." 로 자른다 */
	static String safe(String lockKey) {
		if (lockKey == null) {
			return "null";
		}
		int length = lockKey.length();
		StringBuilder sb = new StringBuilder(Math.min(length, 201));
		for (int i = 0; i < length; i++) {
			char c = lockKey.charAt(i);
			sb.append((c < 0x20 || c == 0x7F) ? '_' : c);
		}
		if (sb.length() > 200) {
			return sb.substring(0, 200) + "...";
		}
		return sb.toString();
	}

	/** 해석된 wait·lease 시간(밀리초). leaseMillis == -1 이면 워치독 */
	record ResolvedTimes(long waitMillis, long leaseMillis) {
	}

	/**
	 * REQUEST scope 용 Servlet 참조 — spring-web 이 없는 소비 앱의 classpath 에서 이 클래스가 로드되지 않도록 격리한다.
	 * {@link #WEB_PRESENT} 가 true 일 때만 호출된다.
	 */
	static final class ServletRequestInspector {

		private ServletRequestInspector() {
		}

		static String currentPattern() {
			if (!(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
				instanceof org.springframework.web.context.request.ServletRequestAttributes attributes)) {
				return "uri";
			}
			jakarta.servlet.http.HttpServletRequest request = attributes.getRequest();
			Object pattern = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
			if (pattern instanceof String text && StringUtils.isNotBlank(text)) {
				return text;
			}
			String uri = request.getRequestURI();
			return StringUtils.isNotBlank(uri) ? uri : "uri";
		}
	}

	/**
	 * 장기 보유 감시 — 획득 성공한 호출만 감시한다. 임계값 간격마다 warn, 첫 warn 에만 holder 스레드 스택을 함께 남긴다.
	 * {@code leaseMillis > 0} 이면 leaseMillis 지나면 추가로 "Lock expired before unlock" warn 1회.
	 */
	static final class LongHoldGuard {

		private final String lockKey;
		private final long thresholdMillis;
		private final long leaseMillis;
		private final long startNanos;
		private final Thread holderThread;
		private volatile boolean firstWarnEmitted;
		private final ScheduledFuture<?> periodic;
		private final ScheduledFuture<?> expiry;

		private LongHoldGuard(ScheduledExecutorService executor, String lockKey, Duration threshold, long leaseMillis) {
			this.lockKey = lockKey;
			this.thresholdMillis = threshold.toMillis();
			this.leaseMillis = leaseMillis;
			this.startNanos = System.nanoTime();
			this.holderThread = Thread.currentThread();
			this.periodic = executor.scheduleAtFixedRate(this::warnLongHold, thresholdMillis, thresholdMillis, TimeUnit.MILLISECONDS);
			this.expiry = leaseMillis > 0
				? executor.schedule(this::warnExpired, leaseMillis, TimeUnit.MILLISECONDS)
				: null;
		}

		static LongHoldGuard start(ScheduledExecutorService executor, String lockKey, Duration threshold, long leaseMillis) {
			if (threshold == null || threshold.isZero() || threshold.isNegative()) {
				return null;
			}
			// threshold 가 1ms 미만이면 scheduleAtFixedRate(period=0) 가 IAE — RedisProperty.validate 가 걸러야 하지만 방어적으로
			if (threshold.compareTo(Duration.ofMillis(1)) < 0) {
				return null;
			}
			return new LongHoldGuard(executor, lockKey, threshold, leaseMillis);
		}

		private void warnLongHold() {
			long elapsedMs = elapsedMillis();
			if (!firstWarnEmitted) {
				firstWarnEmitted = true;
				String stack = Arrays.stream(holderThread.getStackTrace()).map(StackTraceElement::toString).collect(Collectors.joining("\n\tat "));
				log.warn("Lock held too long. lockKey={}, elapsedMs={}, leaseTimeMs={}, holderThread={}\n\tat {}",
					safe(lockKey), elapsedMs, leaseMillis, holderThread.getName(), stack);
			} else {
				log.warn("Lock held too long. lockKey={}, elapsedMs={}, leaseTimeMs={}, holderThread={}",
					safe(lockKey), elapsedMs, leaseMillis, holderThread.getName());
			}
		}

		private void warnExpired() {
			log.warn("Lock expired before unlock. lockKey={}, elapsedMs={}, leaseTimeMs={}", safe(lockKey), elapsedMillis(), leaseMillis);
		}

		void cancel() {
			periodic.cancel(false);
			if (expiry != null) {
				expiry.cancel(false);
			}
			long elapsedMs = elapsedMillis();
			if (elapsedMs >= thresholdMillis) {
				log.info("Long lock hold released. lockKey={}, elapsedMs={}, leaseTimeMs={}", safe(lockKey), elapsedMs, leaseMillis);
			}
		}

		private long elapsedMillis() {
			return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
		}
	}
}
