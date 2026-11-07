package com.corelib.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.exception.CoreException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 Redis(컨테이너)로 RedisUtils·캐시·락 확인 — Docker 가 없으면 건너뛴다 */
@Testcontainers(disabledWithoutDocker = true)
class RedisIntegrationTest {

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

	public record Product(Long id, String name, BigDecimal price, LocalDateTime updatedAt) {
	}

	public static class ProductService {

		final AtomicInteger loads = new AtomicInteger();

		@Cacheable(cacheNames = "product", key = "#id")
		public Product find(Long id) {
			loads.incrementAndGet();
			return new Product(id, "키보드", new BigDecimal("39000"), LocalDateTime.of(2026, 11, 7, 10, 0));
		}
	}

	public static class StockService {

		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final CountDownLatch globalEntered = new CountDownLatch(1);
		final CountDownLatch globalRelease = new CountDownLatch(1);

		@RedisLock(key = "'stock:' + #itemId", waitTime = 200, timeUnit = TimeUnit.MILLISECONDS)
		public String hold(Long itemId) throws InterruptedException {
			entered.countDown();
			release.await(5, TimeUnit.SECONDS);
			return "held";
		}

		@RedisLock(key = "'g:' + #itemId", scope = LockScope.GLOBAL, waitTime = 200, timeUnit = TimeUnit.MILLISECONDS)
		public String globalHold(Long itemId) throws InterruptedException {
			globalEntered.countDown();
			globalRelease.await(5, TimeUnit.SECONDS);
			return "held";
		}
	}

	public static class SlowService {

		@RedisLock(key = "'slow:' + #id", waitTime = 2, timeUnit = TimeUnit.SECONDS)
		public String longHold(Long id) throws InterruptedException {
			Thread.sleep(500);
			return "ok";
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class Services {

		@Bean
		ProductService productService() {
			return new ProductService();
		}

		@Bean
		StockService stockService() {
			return new StockService();
		}

		@Bean
		SlowService slowService() {
			return new SlowService();
		}
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(RedisInitialize.class, RedisLockInitialize.class, RedisStartupReport.class,
			RedisAutoConfiguration.class, CacheAutoConfiguration.class, AopAutoConfiguration.class))
		.withUserConfiguration(Services.class)
		.withPropertyValues("spring.data.redis.host=" + REDIS.getHost(), "spring.data.redis.port=" + REDIS.getMappedPort(6379),
			"core.redis.key-prefix=test:shop", "core.redis.trusted-packages=com.corelib.redis");

	@Test
	void RedisUtils_자료구조와_prefix() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(RedisStartupReport.class).report().getDetail()).contains("standalone", "prefix=test:shop", "캐시 Redis", "락 Redisson");

			RedisUtils.Strings.set("greeting", "안녕", Duration.ofMinutes(1));
			assertThat(RedisUtils.Strings.get("greeting")).isEqualTo("안녕");
			assertThat(RedisUtils.getExpire("greeting")).isBetween(1L, 60L);
			assertThat(RedisUtils.Strings.setIfAbsent("greeting", "x", Duration.ofMinutes(1))).isFalse();
			assertThat(RedisUtils.Strings.increment("visits", 5)).isEqualTo(5);
			assertThat(RedisUtils.Strings.get("visits", Long.class)).isEqualTo(5L);

			Product product = new Product(1L, "마우스", new BigDecimal("15000"), LocalDateTime.of(2026, 1, 1, 9, 0));
			RedisUtils.Strings.set("product:1", product);
			assertThat(RedisUtils.Strings.get("product:1", Product.class)).isEqualTo(product);

			assertThat(RedisUtils.Sets.add("tags", "a", "b", "a")).isEqualTo(2);
			assertThat(RedisUtils.Sets.contains("tags", "b")).isTrue();

			RedisUtils.Lists.pushFirst("recent", 1L);
			RedisUtils.Lists.pushFirst("recent", 2L);
			RedisUtils.Lists.pushFirst("recent", 3L);
			RedisUtils.Lists.trim("recent", 0, 1);
			assertThat(RedisUtils.Lists.all("recent")).containsExactly(3L, 2L);

			RedisUtils.Hashes.putAll("cart", Map.of("item-1", 2, "item-2", 1));
			assertThat(RedisUtils.Hashes.increment("cart", "item-1", 3)).isEqualTo(5);
			assertThat(RedisUtils.Hashes.get("cart", "item-1", Integer.class)).isEqualTo(5);
			assertThat(RedisUtils.Hashes.entries("cart")).containsOnlyKeys("item-1", "item-2");

			RedisUtils.ZSets.incrementScore("ranking", "redis", 3);
			RedisUtils.ZSets.incrementScore("ranking", "spring", 5);
			RedisUtils.ZSets.incrementScore("ranking", "java", 1);
			assertThat(RedisUtils.ZSets.top("ranking", 2)).extracting(RedisDto.ScoredValue::value).containsExactly("spring", "redis");
			assertThat(RedisUtils.ZSets.rank("ranking", "java", true)).isEqualTo(2L);

			assertThat(RedisUtils.keys("*", 100)).contains("greeting", "visits", "tags", "recent", "cart", "ranking");
			assertThat(RedisUtils.exists("greeting")).isTrue();
			assertThat(RedisUtils.delete("greeting")).isTrue();
			assertThat(RedisUtils.exists("greeting")).isFalse();
		});
	}

	@Test
	void Cacheable_은_Redis_에_JSON_으로_캐시되고_두번째는_메서드를_부르지_않는다() {
		runner.run(context -> {
			// @Cacheable 이 CGLIB proxy 를 씌우므로 필드(loads)는 target 에서 꺼낸다 — proxy 서브클래스의 필드는 초기화되지 않는다
			ProductService service = context.getBean(ProductService.class);
			ProductService target = AopTestUtils.getTargetObject(service);
			Product first = service.find(42L);
			Product second = service.find(42L);

			assertThat(second).isEqualTo(first);
			assertThat(target.loads).hasValue(1);
			assertThat(RedisUtils.keys("product::*", 10)).containsExactly("product::42");
		});
	}

	@Test
	void 같은_키를_잡은_동안_다른_요청은_409() {
		runner.run(context -> {
			// 메서드 호출은 proxy 로 (AOP 적용), 필드(latch) 접근은 target 으로 — proxy 는 필드가 null
			StockService proxy = context.getBean(StockService.class);
			StockService target = AopTestUtils.getTargetObject(proxy);
			CompletableFuture<String> holder = CompletableFuture.supplyAsync(() -> {
				try {
					return proxy.hold(7L);
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
			});
			assertThat(target.entered.await(5, TimeUnit.SECONDS)).isTrue();

			assertThatThrownBy(() -> proxy.hold(7L))
				.isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorCode()).isEqualTo(RedisError.LOCK_CONFLICT));

			target.release.countDown();
			assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("held");
			assertThat(proxy.hold(7L)).isEqualTo("held");    // 해제 뒤에는 다시 잡힌다
		});
	}

	@Test
	void GLOBAL_scope_분산_락_은_global_prefix_로_저장된다() {
		runner.run(context -> {
			StockService proxy = context.getBean(StockService.class);
			StockService target = AopTestUtils.getTargetObject(proxy);
			CompletableFuture<String> holder = CompletableFuture.supplyAsync(() -> {
				try {
					return proxy.globalHold(7L);
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
			});
			assertThat(target.globalEntered.await(5, TimeUnit.SECONDS)).isTrue();

			assertThat(RedisUtils.keys("lock:global:*", 10)).anyMatch(k -> k.contains("lock:global:g:7"));

			assertThatThrownBy(() -> proxy.globalHold(7L))
				.isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorCode()).isEqualTo(RedisError.LOCK_CONFLICT));

			target.globalRelease.countDown();
			assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("held");
		});
	}

	@Test
	@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
	void long_hold_임계값을_넘기면_warn_로그(org.springframework.boot.test.system.CapturedOutput output) {
		runner.withPropertyValues("core.redis.lock.long-hold-warn-threshold=200ms").run(context -> {
			SlowService service = context.getBean(SlowService.class);
			assertThat(service.longHold(1L)).isEqualTo("ok");
			// 스케줄러가 비동기로 warn 하므로 짧게 기다려 로그가 flush 될 시간을 준다
			Thread.sleep(200);
			assertThat(output.getAll()).contains("Lock held too long");
		});
	}
}
