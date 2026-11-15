package com.corelib.banword;

import static org.assertj.core.api.Assertions.assertThat;

import com.corelib.common.exception.CoreException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.cache.RedisCacheManager;

class BanWordInitializeTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(BanWordInitialize.class));

	@Test
	@DisplayName("BanWordSource 빈이 없으면 SOURCE_NOT_REGISTERED 로 기동 실패")
	void sourceMissingFailsStartup() {
		runner.run(context -> {
			assertThat(context).hasFailed();
			Throwable failure = context.getStartupFailure();
			CoreException core = findCore(failure);
			assertThat(core).isNotNull();
			assertThat(core.getErrorCode()).isEqualTo(BanWordError.SOURCE_NOT_REGISTERED);
		});
	}

	@Test
	@DisplayName("Source 빈 있음 + RedisCacheManager 빈 없음 → BanWordDirectService 선택")
	void directServiceWhenNoRedisCacheManagerBean() {
		runner.withUserConfiguration(SourceOnlyConfig.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(BanWordCacheService.class);
				assertThat(context.getBean(BanWordCacheService.class)).isInstanceOf(BanWordDirectService.class);
			});
	}

	@Test
	@DisplayName("Source 빈 있음 + RedisCacheManager 타입 빈 있음 → BanWordRedisService 선택")
	void redisServiceWhenRedisCacheManagerBeanPresent() {
		runner.withUserConfiguration(SourceOnlyConfig.class, RedisCacheManagerConfig.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(BanWordCacheService.class);
				assertThat(context.getBean(BanWordCacheService.class)).isInstanceOf(BanWordRedisService.class);
			});
	}

	@Test
	@DisplayName("Redis 가 classpath 에 없으면 BanWordDirectService 로 분기")
	void directServiceWhenNoRedisClass() {
		runner.withUserConfiguration(SourceOnlyConfig.class)
			.withClassLoader(new FilteredClassLoader(RedisCacheManager.class))
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(BanWordCacheService.class);
				assertThat(context.getBean(BanWordCacheService.class)).isInstanceOf(BanWordDirectService.class);
			});
	}

	@Test
	@DisplayName("maxTextLength=0 이면 INVALID_MAX_TEXT_LENGTH 로 기동 실패")
	void invalidMaxTextLengthFailsStartup() {
		runner.withUserConfiguration(SourceOnlyConfig.class)
			.withPropertyValues("core.banword.max-text-length=0")
			.run(context -> {
				assertThat(context).hasFailed();
				CoreException core = findCore(context.getStartupFailure());
				assertThat(core).isNotNull();
				assertThat(core.getErrorCode()).isEqualTo(BanWordError.INVALID_MAX_TEXT_LENGTH);
			});
	}

	private static CoreException findCore(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			if (t instanceof CoreException core) {
				return core;
			}
		}
		return null;
	}

	@TestConfiguration
	static class SourceOnlyConfig {

		@Bean
		BanWordSource stubSource() {
			return new BanWordSource() {
				@Override
				public List<String> getBanWords() {
					return Collections.emptyList();
				}

				@Override
				public List<String> getWhiteWords() {
					return Collections.emptyList();
				}
			};
		}
	}

	@TestConfiguration
	static class RedisCacheManagerConfig {

		/**
		 * BanWordInitialize 의 Redis 분기 조건은 {@code @ConditionalOnClass(RedisCacheManager)} +
		 * {@code @ConditionalOnBean(RedisCacheManager.class)} 이다. 실제 corelib:redis 는
		 * {@code "cacheManager"} 이름으로 RedisCacheManager 를 등록하지만, 조건은 타입 기반이므로
		 * 빈 이름은 기본값으로 둔다. 테스트에서는 실제 Redis 연결이 없으므로 Mockito 로 mock 한다.
		 */
		@Bean
		RedisCacheManager cacheManager() {
			return Mockito.mock(RedisCacheManager.class);
		}
	}
}
