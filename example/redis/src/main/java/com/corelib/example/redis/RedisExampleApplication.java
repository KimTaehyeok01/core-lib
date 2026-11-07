package com.corelib.example.redis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 예제 앱 진입점 — redis 모듈 기능을 쇼핑몰 시나리오로 보여준다.
 * <ul>
 *   <li>상품 상세 캐시 — {@code @Cacheable} 로 두 번째 호출부터는 Redis 가 돌려준다</li>
 *   <li>선착순 쿠폰 발급 — {@code @RedisLock(scope = GLOBAL)} 로 (쿠폰, 멤버) 중복 요청을 모든 진입점에 걸쳐 배제한다</li>
 *   <li>인기 검색어 — {@code RedisUtils.ZSets} 로 조회수 누적과 상위 N 개 조회</li>
 * </ul>
 */
@SpringBootApplication
public class RedisExampleApplication {

	public static void main(String[] args) {
		SpringApplication.run(RedisExampleApplication.class, args);
	}
}
