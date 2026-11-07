package com.corelib.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.exception.CoreException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/** Redis 서버 없이 확인하는 것 — 활성 조건·미초기화 안내·기동 실패 */
class RedisInitializeTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(RedisInitialize.class, RedisLockInitialize.class, RedisStartupReport.class,
			RedisAutoConfiguration.class, AopAutoConfiguration.class));

	@Test
	void 접속_키가_없으면_꺼지고_기동_안내가_무엇을_넣을지_알린다() {
		runner.run(context -> {
			assertThat(context).hasNotFailed().doesNotHaveBean(RedisInitialize.class);
			assertThat(context.getBean(RedisStartupReport.class).report().isReady()).isFalse();
			assertThat(context.getBean(RedisStartupReport.class).report().getDetail()).contains("spring.data.redis.host");
		});
	}

	@Test
	void 접속_키_판정() {
		assertThat(RedisInitialize.OnRedisConfiguredCondition.configuredKey(new MockEnvironment())).isNull();
		assertThat(RedisInitialize.OnRedisConfiguredCondition.configuredKey(new MockEnvironment().withProperty("spring.data.redis.port", "6379"))).isNull();
		assertThatThrownBy(() -> RedisInitialize.OnRedisConfiguredCondition.configuredKey(
			new MockEnvironment().withProperty("spring.data.redis.host", " "))).isInstanceOf(CoreException.class);
		assertThat(RedisInitialize.OnRedisConfiguredCondition.configuredKey(new MockEnvironment().withProperty("spring.data.redis.host", "redis")))
			.isEqualTo("spring.data.redis.host");
		assertThat(RedisInitialize.OnRedisConfiguredCondition.configuredKey(
			new MockEnvironment().withProperty("spring.data.redis.cluster.nodes", "a:6379,b:6379"))).isEqualTo("spring.data.redis.cluster.nodes");
		assertThat(RedisInitialize.OnRedisConfiguredCondition.configuredKey(
			new MockEnvironment().withProperty("spring.data.redis.sentinel.nodes[0]", "a:26379"))).isEqualTo("spring.data.redis.sentinel.nodes");
	}

	@Test
	void Redis_에_닿지_않으면_기동_실패() {
		runner.withPropertyValues("spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1", "spring.data.redis.connect-timeout=500ms")
			  .run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseInstanceOf(java.net.ConnectException.class)
												 .satisfies(failure -> assertThat(rootCore(failure)).isNotNull()));
	}

	@Test
	void 잘못된_key_prefix_는_기동_실패() {
		runner.withPropertyValues("spring.data.redis.host=127.0.0.1", "core.redis.key-prefix=prod shop")
			  .run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 빈_host_는_기동_실패() {
		runner.withPropertyValues("spring.data.redis.host=")
			  .run(context -> {
				  assertThat(context).hasFailed();
				  // ConditionEvaluator 가 CoreException 을 IllegalStateException 으로 감싸므로 root cause 를 unwrap
				  assertThat(rootCore(context.getStartupFailure())).isNotNull()
					  .hasMessageContaining("빈 문자열");
			  });
	}

	@Test
	void 빈_cluster_nodes_단일값_은_기동_실패() {
		runner.withPropertyValues("spring.data.redis.cluster.nodes[0]=")
			  .run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 공백_cluster_nodes_원소는_기동_실패() {
		runner.withPropertyValues("spring.data.redis.cluster.nodes[0]= ")
			  .run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 빈_sentinel_nodes_는_기동_실패() {
		runner.withPropertyValues("spring.data.redis.sentinel.master=mymaster", "spring.data.redis.sentinel.nodes[0]=")
			  .run(context -> assertThat(context).hasFailed());
	}

	private static CoreException rootCore(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			if (t instanceof CoreException core) {
				return core;
			}
		}
		return null;
	}
}
