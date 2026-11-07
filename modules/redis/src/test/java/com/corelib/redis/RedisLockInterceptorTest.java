package com.corelib.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

class RedisLockInterceptorTest {

	static class CouponService {

		final AtomicInteger calls = new AtomicInteger();

		@RedisLock(key = "'coupon:' + #couponId", waitTime = 1)
		public String issue(Long couponId) {
			calls.incrementAndGet();
			return "ok";
		}

		@RedisLock(key = "'stats:' + #day", waitTime = 0, failOpen = true)
		public String recalculate(String day) {
			calls.incrementAndGet();
			return "done";
		}

		@RedisLock(key = "'lease:' + #id", leaseTime = 10)
		public String leased(Long id) {
			return "ok";
		}

		@RedisLock(key = "'o:' + #id", scope = LockScope.GLOBAL, waitTime = 1)
		public String globalIssue(Long id) {
			calls.incrementAndGet();
			return "ok";
		}

		@RedisLock(key = "'req:' + #id", waitTime = 1)
		public String requestIssue(Long id) {
			calls.incrementAndGet();
			return "ok";
		}

		@RedisLock(key = "'str:' + #id", waitTimeString = "${app.wait}")
		public String fromProperty(Long id) {
			calls.incrementAndGet();
			return "ok";
		}

		@RedisLock(key = "'bad:' + #id", waitTimeString = "abc")
		public String badWait(Long id) {
			calls.incrementAndGet();
			return "ok";
		}

		@RedisLock(key = "'badLease:' + #id", leaseTimeString = "-5s")
		public String badLease(Long id) {
			calls.incrementAndGet();
			return "ok";
		}

		// null-key 경로 검증용 — SpEL '#name' 이 null 로 평가되면 interceptor 가 BAD_REQUEST 를 던진다
		@RedisLock(key = "#name")
		public String issueByName(String name) {
			calls.incrementAndGet();
			return "ok";
		}
	}

	private RedissonClient redisson;
	private RLock lock;
	private CouponService target;

	@BeforeEach
	void setUp() {
		redisson = mock(RedissonClient.class);
		lock = mock(RLock.class);
		when(redisson.getLock(anyString())).thenReturn(lock);
		target = new CouponService();
	}

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	void 락을_잡으면_실행하고_해제한다_키에는_prefix_가_붙는다() throws Exception {
		when(lock.tryLock(1000L, -1L, TimeUnit.MILLISECONDS)).thenReturn(true);

		assertThat(proxy(redisson, "prod:shop").issue(10L)).isEqualTo("ok");

		// 기본 scope=REQUEST + 요청 컨텍스트 밖 → "uri" fallback (RedisLockInterceptor.requestPattern)
		verify(redisson).getLock("prod:shop:lock:uri:coupon:10");
		verify(lock).unlock();
		assertThat(target.calls).hasValue(1);
	}

	@Test
	void 양수_leaseTime_은_그대로_넘긴다() throws Exception {
		when(lock.tryLock(3000L, 10000L, TimeUnit.MILLISECONDS)).thenReturn(true);

		proxy(redisson, null).leased(1L);

		// 기본 scope=REQUEST + 요청 컨텍스트 밖 → "uri" fallback (RedisLockInterceptor.requestPattern)
		verify(redisson).getLock("lock:uri:lease:1");
	}

	@Test
	void 대기_시간_안에_못_잡으면_409_이고_실행하지_않는다() throws Exception {
		when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false);

		assertThatThrownBy(() -> proxy(redisson, null).issue(10L))
			.isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorCode()).isEqualTo(RedisError.LOCK_CONFLICT));
		assertThat(target.calls).hasValue(0);
		verify(lock, never()).unlock();
	}

	@Test
	void Redis_장애면_500_이고_실행하지_않는다() throws Exception {
		when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(new RedisConnectionException("down"));

		assertThatThrownBy(() -> proxy(redisson, null).issue(10L))
			.isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorCode()).isEqualTo(RedisError.UNAVAILABLE));
		assertThat(target.calls).hasValue(0);
	}

	@Test
	void failOpen_이면_못_잡아도_락_없이_실행한다() throws Exception {
		when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(new RedisConnectionException("down"));

		assertThat(proxy(redisson, null).recalculate("2026-11-07")).isEqualTo("done");
		verify(lock, never()).unlock();
	}

	@Test
	void Redis_미설정이면_락_없이_통과하지_않고_500() {
		assertThatThrownBy(() -> proxy(null, null).issue(10L))
			.isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorCode()).isEqualTo(RedisError.UNAVAILABLE));
		assertThat(target.calls).hasValue(0);
	}

	@Test
	void 키가_비면_400() {
		// 'coupon:' + null → "coupon:null" 로 수렴해 blank 가 아니므로, SpEL 이 그대로 null 로 평가되는 단일 변수 식을 쓴다
		assertThatThrownBy(() -> proxy(redisson, null).issueByName(null))
			.isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.BAD_REQUEST));
	}

	@Test
	void 대기_중_인터럽트는_409_이고_플래그를_되살린다() throws Exception {
		when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(new InterruptedException());

		assertThatThrownBy(() -> proxy(redisson, null).issue(10L))
			.isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorCode()).isEqualTo(RedisError.LOCK_CONFLICT));
		assertThat(Thread.interrupted()).isTrue();
	}

	@Test
	void 해제_전에_만료돼도_결과는_그대로() throws Exception {
		when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
		org.mockito.Mockito.doThrow(new IllegalMonitorStateException()).when(lock).unlock();

		assertThat(proxy(redisson, null).issue(10L)).isEqualTo("ok");
	}

	@Test
	void GLOBAL_scope_는_global_prefix_를_붙인다() throws Exception {
		when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);

		assertThat(proxy(redisson, "prod:shop").globalIssue(10L)).isEqualTo("ok");

		verify(redisson).getLock("prod:shop:lock:global:o:10");
		verify(lock).unlock();
	}

	@Test
	void 요청_컨텍스트_밖_REQUEST_scope_는_uri_fallback() throws Exception {
		RequestContextHolder.resetRequestAttributes();
		when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);

		assertThat(proxy(redisson, null).requestIssue(10L)).isEqualTo("ok");

		verify(redisson).getLock("lock:uri:req:10");
	}

	@Test
	void REQUEST_scope_는_매칭_패턴을_키에_포함한다() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}/pay");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
		try {
			when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);

			assertThat(proxy(redisson, null).requestIssue(10L)).isEqualTo("ok");

			verify(redisson).getLock("lock:/orders/{id}/pay:req:10");
		} finally {
			RequestContextHolder.resetRequestAttributes();
		}
	}

	@Test
	void waitTimeString_placeholder_는_환경변수로_해석된다() throws Exception {
		MockEnvironment environment = new MockEnvironment().withProperty("app.wait", "500ms");
		when(lock.tryLock(500L, -1L, TimeUnit.MILLISECONDS)).thenReturn(true);

		assertThat(proxy(redisson, null, environment).fromProperty(1L)).isEqualTo("ok");

		verify(lock).tryLock(500L, -1L, TimeUnit.MILLISECONDS);
	}

	@Test
	void waitTimeString_형식_오류는_invoke_시_500() {
		assertThatThrownBy(() -> proxy(redisson, null).badWait(1L))
			.isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER));
		assertThat(target.calls).hasValue(0);
	}

	@Test
	void leaseTimeString_음수는_invoke_시_500() {
		assertThatThrownBy(() -> proxy(redisson, null).badLease(1L))
			.isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER));
		assertThat(target.calls).hasValue(0);
	}

	private CouponService proxy(RedissonClient client, String keyPrefix) {
		return proxy(client, keyPrefix, new MockEnvironment());
	}

	private CouponService proxy(RedissonClient client, String keyPrefix, Environment environment) {
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		if (client != null) {
			beanFactory.registerSingleton("redissonClient", client);
		}
		RedisProperty property = new RedisProperty();
		property.setKeyPrefix(keyPrefix);
		beanFactory.registerSingleton("redisProperty", property);
		Advisor advisor = RedisLockInitialize.redisLockAdvisor(beanFactory.getBeanProvider(RedissonClient.class), beanFactory.getBeanProvider(RedisProperty.class), environment);
		ProxyFactory factory = new ProxyFactory(target);
		factory.setProxyTargetClass(true);
		factory.addAdvisor(advisor);
		return (CouponService) factory.getProxy();
	}
}
