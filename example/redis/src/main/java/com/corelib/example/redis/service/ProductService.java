package com.corelib.example.redis.service;

import com.corelib.example.redis.dto.ProductDto;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 상품 상세 서비스 — 상품 상세를 Redis 로 캐시한다.
 * <ul>
 *   <li>{@code @Cacheable("product")} — 두 번째 호출부터는 Redis 가 돌려주고 메서드는 들어오지 않는다 ({@link #loadCount()} 로 확인)</li>
 *   <li>캐시 키는 "{redis-example}:product::{id}" 로 저장된다 (core.redis.key-prefix + cacheName + key)</li>
 *   <li>캐시 TTL 등 정책은 {@code RedisCacheManagerBuilderCustomizer} 빈으로 (예제는 기본값)</li>
 * </ul>
 */
@Slf4j
@Service
public class ProductService {

	/** 실제 메서드가 몇 번 들어왔는지 (캐시 적중 확인용) */
	private final AtomicInteger loads = new AtomicInteger();

	/**
	 * 상품 상세 조회 — 첫 호출은 메서드가 실행되고 Redis 에 저장된다.
	 * 두 번째 호출부터는 캐시 적중으로 메서드 안에 들어오지 않아 {@link #loads} 가 오르지 않는다.
	 */
	@Cacheable(cacheNames = "product", key = "#id")
	public ProductDto.Response find(Long id) {
		loads.incrementAndGet();
		log.info("DB(가정)에서 상품 로드. id={}", id);
		return new ProductDto.Response(id, "상품-" + id, new BigDecimal("39000"), LocalDateTime.now());
	}

	/** 저장된 상품 캐시를 비운다 — 가격·이름이 바뀌었을 때 호출 */
	@CacheEvict(cacheNames = "product", key = "#id")
	public void evict(Long id) {
		log.info("상품 캐시 삭제. id={}", id);
	}

	/** 지금까지 실제 메서드가 몇 번 들어왔는지 — 캐시 동작 확인 */
	public int loadCount() {
		return loads.get();
	}
}
