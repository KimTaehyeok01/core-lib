package com.corelib.banword;

import com.corelib.common.exception.CoreException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.cache.RedisCacheManager;

/**
 * banword 자동 구성 (v2).
 * <ul>
 *   <li>{@link BanWordProperty} 를 바인딩하고 기동 시 validate</li>
 *   <li>{@link BanWordSource} 빈이 없으면 즉시 {@link BanWordError#SOURCE_NOT_REGISTERED} 로 기동 실패</li>
 *   <li>캐시 구현을 조건부로 선택 — {@link RedisCacheManager} 타입 빈 있으면 {@link BanWordRedisService}, 없으면 {@link BanWordDirectService}</li>
 * </ul>
 * 라이브러리는 JPA/Entity 를 더 이상 소유하지 않는다 — 데이터 공급은 전적으로 {@link BanWordSource} 구현의 책임.
 */
@AutoConfiguration(afterName = "com.corelib.redis.RedisInitialize")
@EnableConfigurationProperties(BanWordProperty.class)
@Import(BanWordInitialize.RedisCacheConfiguration.class)
public class BanWordInitialize {

	public BanWordInitialize(BanWordProperty property, ObjectProvider<BanWordSource> sourceProvider) {
		property.validate();
		BanWordSource source = sourceProvider.getIfAvailable();
		if (source == null) {
			throw new CoreException(BanWordError.SOURCE_NOT_REGISTERED);
		}
	}

	@Bean
	@ConditionalOnMissingBean
	public BanWordPreprocessor banWordPreprocessor() {
		return new BanWordPreprocessor();
	}

	@Bean
	@ConditionalOnMissingBean
	public BanWordMatcher banWordMatcher(BanWordCacheService cacheService, BanWordPreprocessor preprocessor) {
		BanWordMatcher matcher = new BanWordMatcher(cacheService, preprocessor);
		if (cacheService instanceof BanWordRedisService redis) {
			redis.bindMatcher(matcher);
		} else if (cacheService instanceof BanWordDirectService direct) {
			direct.bindMatcher(matcher);
		}
		return matcher;
	}

	@Bean
	@ConditionalOnMissingBean
	public BanWordUtils banWordUtils(BanWordPreprocessor preprocessor, BanWordMatcher matcher, BanWordProperty property) {
		return new BanWordUtils(preprocessor, matcher, property);
	}

	/** Redis 가 없거나 {@link RedisCacheManager} 타입 빈이 없을 때의 폴백. */
	@Bean
	@ConditionalOnMissingBean(BanWordCacheService.class)
	public BanWordCacheService banWordDirectService(BanWordSource source) {
		return new BanWordDirectService(source);
	}

	/**
	 * Redis 환경 — 클래스패스에 {@link RedisCacheManager} 존재 시에만 평가된다.
	 * 내부 Configuration 자체가 {@code @ConditionalOnClass} 로 가드되므로, Redis 미의존 소비 앱에서
	 * {@link RedisCacheManager} 심볼이 참조되지 않는다 ({@code @ConditionalOnBean} 의 Class 값 로딩 회피).
	 * corelib:redis 는 {@code "cacheManager"} 이름으로 {@link RedisCacheManager} 를 자동 등록한다.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(RedisCacheManager.class)
	static class RedisCacheConfiguration {

		@Bean
		@ConditionalOnMissingBean(BanWordCacheService.class)
		@ConditionalOnBean(RedisCacheManager.class)
		public BanWordCacheService banWordRedisService(BanWordSource source, RedisCacheManager cacheManager) {
			return new BanWordRedisService(source, cacheManager);
		}
	}
}
