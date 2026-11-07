package com.corelib.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 기동 시 @RedisLock 의 waitTimeString·leaseTimeString 사전 검증 — 잘못된 값은 첫 요청이 아닌 기동 로그에서 즉시 노출 */
class RedisLockTimeStringValidatorTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(RedisLockInitialize.class));

	static class ValidWait {

		@RedisLock(key = "'x:' + #id", waitTimeString = "1s")
		public void run(Long id) {
		}
	}

	static class BadWaitFormat {

		@RedisLock(key = "'x:' + #id", waitTimeString = "abc")
		public void run(Long id) {
		}
	}

	static class NegativeLease {

		@RedisLock(key = "'x:' + #id", leaseTimeString = "-5s")
		public void run(Long id) {
		}
	}

	static class SubMillisLease {

		@RedisLock(key = "'x:' + #id", leaseTimeString = "500us")
		public void run(Long id) {
		}
	}

	static class UnknownPlaceholder {

		@RedisLock(key = "'x:' + #id", waitTimeString = "${app.unknown}")
		public void run(Long id) {
		}
	}

	static class PlaceholderWithDefault {

		@RedisLock(key = "'x:' + #id", waitTimeString = "${app.wait:2s}")
		public void run(Long id) {
		}
	}

	@Test
	void 정상_duration_은_기동_성공() {
		runner.withBean(ValidWait.class).run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void waitTimeString_abc_면_기동_실패() {
		runner.withBean(BadWaitFormat.class).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasMessageContaining("waitTimeString");
		});
	}

	@Test
	void leaseTimeString_음수는_기동_실패() {
		runner.withBean(NegativeLease.class).run(context -> assertThat(context).hasFailed());
	}

	@Test
	void leaseTimeString_1ms_미만은_기동_실패() {
		runner.withBean(SubMillisLease.class).run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 미정의_placeholder_는_기동_실패() {
		runner.withBean(UnknownPlaceholder.class).run(context -> assertThat(context).hasFailed());
	}

	@Test
	void placeholder_기본값은_property_없어도_기동_성공() {
		runner.withBean(PlaceholderWithDefault.class).run(context -> assertThat(context).hasNotFailed());
	}
}
