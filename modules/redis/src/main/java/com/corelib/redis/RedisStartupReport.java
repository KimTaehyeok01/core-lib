package com.corelib.redis;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import java.time.Duration;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.data.redis.cache.RedisCacheManager;

/** 기동 안내 — 접속 대상(계정·비밀번호 제외)·key-prefix·캐시·락. 꺼져 있으면 무엇을 넣으면 되는지 */
@AutoConfiguration
public class RedisStartupReport implements StartupReporter {

	private static final String NAME = "RedisUtils";

	private final ObjectProvider<RedisInitialize> initialize;
	private final ObjectProvider<RedisProperty> property;
	private final ObjectProvider<RedisConnectionDetails> connectionDetails;
	private final ListableBeanFactory beanFactory;

	public RedisStartupReport(ObjectProvider<RedisInitialize> initialize, ObjectProvider<RedisProperty> property,
		ObjectProvider<RedisConnectionDetails> connectionDetails, ListableBeanFactory beanFactory) {
		this.initialize = initialize;
		this.property = property;
		this.connectionDetails = connectionDetails;
		this.beanFactory = beanFactory;
	}

	@Override
	public StartupReport report() {
		RedisConnectionDetails details = connectionDetails.getIfAvailable();
		RedisProperty value = property.getIfAvailable();
		if (initialize.getIfAvailable() == null || details == null || value == null || !RedisUtils.isInitialized()) {
			return StartupReport.notReady(NAME, "spring.data.redis.host·url·sentinel.nodes·cluster.nodes 미설정 — RedisUtils·@RedisLock 호출 시 오류");
		}
		String prefix = value.getKeyPrefix() == null ? "없음" : value.getKeyPrefix();
		return StartupReport.ready(NAME,
			RedissonConfigs.describe(details) + ", prefix=" + prefix + ", 캐시 " + cache() + ", 락 " + lock() + ", longHoldWarn=" + longHoldWarn(value));
	}

	private static String longHoldWarn(RedisProperty value) {
		if (value.getLock() == null) {
			return "off";
		}
		Duration threshold = value.getLock().getLongHoldWarnThreshold();
		return threshold == null || threshold.isZero() ? "off" : threshold.toString();
	}

	private String cache() {
		Class<?> type = beanFactory.containsBean(RedisInitialize.CACHE_MANAGER_BEAN) ? beanFactory.getType(RedisInitialize.CACHE_MANAGER_BEAN) : null;
		return type != null && RedisCacheManager.class.isAssignableFrom(type) ? "Redis" : "앱 cacheManager";
	}

	private String lock() {
		int count = beanFactory.getBeanNamesForType(RedissonClient.class).length;
		return count == 1 ? "Redisson" : "RedissonClient " + count + "개 (@Primary 필요)";
	}
}
