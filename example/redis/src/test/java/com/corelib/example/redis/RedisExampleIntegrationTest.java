package com.corelib.example.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.corelib.example.redis.service.ProductService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 Redis(컨테이너) 와 예제 앱 — 상품 캐시·선착순 쿠폰·인기 검색어 end-to-end */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = RedisExampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class RedisExampleIntegrationTest {

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

	@DynamicPropertySource
	static void redisProps(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	private final TestRestTemplate rest;
	private final ProductService productService;

	RedisExampleIntegrationTest(TestRestTemplate rest, ProductService productService) {
		this.rest = rest;
		this.productService = productService;
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void 상품_캐시_는_두번째_호출에서_메서드가_들어오지_않는다() {
		int before = productService.loadCount();

		ResponseEntity<Map> first = rest.getForEntity("/products/find?id=1", Map.class);
		ResponseEntity<Map> second = rest.getForEntity("/products/find?id=1", Map.class);

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat((Map) first.getBody().get("data")).isEqualTo((Map) second.getBody().get("data"));
		assertThat(productService.loadCount() - before).isEqualTo(1);
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void 쿠폰_선착순_재고_1_에_동시_2건_은_하나만_성공() throws Exception {
		// 재고=1 인 2번 쿠폰에 서로 다른 memberId 2개가 동시에 발급 요청
		CountDownLatch barrier = new CountDownLatch(1);
		CompletableFuture<ResponseEntity<Map>> a = CompletableFuture.supplyAsync(() -> {
			await(barrier);
			return rest.postForEntity("/coupons/issue", Map.of("couponId", 2, "memberId", 101), Map.class);
		});
		CompletableFuture<ResponseEntity<Map>> b = CompletableFuture.supplyAsync(() -> {
			await(barrier);
			return rest.postForEntity("/coupons/issue", Map.of("couponId", 2, "memberId", 102), Map.class);
		});
		barrier.countDown();

		ResponseEntity<Map> aResp = a.get(10, TimeUnit.SECONDS);
		ResponseEntity<Map> bResp = b.get(10, TimeUnit.SECONDS);

		long successCount = List.of(aResp, bResp).stream()
			.filter(r -> r.getStatusCode().is2xxSuccessful() && Boolean.TRUE.equals(r.getBody().get("success")))
			.count();
		long conflictCount = List.of(aResp, bResp).stream()
			.filter(r -> r.getStatusCode().value() == 409 || "REDIS-LOCK".equals(r.getBody().get("code"))
				|| "COUPON_SOLD_OUT".equals(r.getBody().get("code")))
			.count();
		assertThat(successCount).isEqualTo(1);
		assertThat(conflictCount).isEqualTo(1);
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void 쿠폰_같은_멤버_재발급은_400() {
		// 재고=3 인 1번 쿠폰, 같은 memberId 로 2회 → 2번째는 ALREADY_ISSUED
		ResponseEntity<Map> first = rest.postForEntity("/coupons/issue", Map.of("couponId", 1, "memberId", 999), Map.class);
		ResponseEntity<Map> second = rest.postForEntity("/coupons/issue", Map.of("couponId", 1, "memberId", 999), Map.class);

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getStatusCode().value()).isEqualTo(400);
		assertThat(second.getBody().get("code")).isEqualTo("COUPON_ALREADY_ISSUED");
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void ZSet_트렌딩_은_점수_내림차순() {
		for (int i = 0; i < 3; i++) {
			rest.postForEntity("/trending/record", Map.of("keyword", "korea"), Map.class);
		}
		for (int i = 0; i < 5; i++) {
			rest.postForEntity("/trending/record", Map.of("keyword", "japan"), Map.class);
		}

		ResponseEntity<Map> response = rest.getForEntity("/trending/top?n=2", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		Map data = (Map) response.getBody().get("data");
		List<Map> items = (List<Map>) data.get("items");
		assertThat(items).hasSize(2);
		assertThat(items.get(0).get("keyword")).isEqualTo("japan");
		assertThat(items.get(1).get("keyword")).isEqualTo("korea");
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
