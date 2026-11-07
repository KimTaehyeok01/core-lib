package com.corelib.redis;

import com.corelib.lang.StringUtils;
import java.time.Duration;
import java.util.List;
import org.redisson.config.BaseConfig;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

/** spring.data.redis.* → Redisson 설정 — Lettuce(캐시·RedisUtils)와 같은 대상에 접속한다. 고르는 순서도 Boot 와 같다 (sentinel → cluster → standalone) */
final class RedissonConfigs {

	private RedissonConfigs() {
	}

	static Config from(RedisConnectionDetails details, RedisProperties properties) {
		Config config = new Config();
		String scheme = isSsl(properties) ? "rediss://" : "redis://";
		RedisConnectionDetails.Sentinel sentinel = details.getSentinel();
		RedisConnectionDetails.Cluster cluster = details.getCluster();
		if (sentinel != null) {
			SentinelServersConfig server = config.useSentinelServers()
												 .setMasterName(sentinel.getMaster())
												 .setDatabase(sentinel.getDatabase());
			sentinel.getNodes().forEach(node -> server.addSentinelAddress(address(scheme, node)));
			if (StringUtils.isNotBlank(sentinel.getUsername())) {
				server.setSentinelUsername(sentinel.getUsername());
			}
			if (StringUtils.isNotBlank(sentinel.getPassword())) {
				server.setSentinelPassword(sentinel.getPassword());
			}
			common(server, details, properties);
		} else if (cluster != null) {
			ClusterServersConfig server = config.useClusterServers();
			cluster.getNodes().forEach(node -> server.addNodeAddress(address(scheme, node)));
			common(server, details, properties);
		} else {
			RedisConnectionDetails.Standalone standalone = details.getStandalone();
			SingleServerConfig server = config.useSingleServer()
											  .setAddress(scheme + host(standalone.getHost()) + ":" + standalone.getPort())
											  .setDatabase(standalone.getDatabase());
			common(server, details, properties);
		}
		return config;
	}

	/** 기동 안내용 접속 대상 — 비밀번호·계정은 넣지 않는다 */
	static String describe(RedisConnectionDetails details) {
		if (details.getSentinel() != null) {
			return "sentinel master=" + details.getSentinel().getMaster() + " " + nodes(details.getSentinel().getNodes());
		}
		if (details.getCluster() != null) {
			return "cluster " + nodes(details.getCluster().getNodes());
		}
		RedisConnectionDetails.Standalone standalone = details.getStandalone();
		return "standalone " + host(standalone.getHost()) + ":" + standalone.getPort() + "/" + standalone.getDatabase();
	}

	private static void common(BaseConfig<?> server, RedisConnectionDetails details, RedisProperties properties) {
		if (StringUtils.isNotBlank(details.getUsername())) {
			server.setUsername(details.getUsername());
		}
		if (StringUtils.isNotBlank(details.getPassword())) {
			server.setPassword(details.getPassword());
		}
		if (StringUtils.isNotBlank(properties.getClientName())) {
			server.setClientName(properties.getClientName());
		}
		// 비우면 Redisson 기본 (명령 3s, 연결 10s)
		millis(properties.getTimeout(), server::setTimeout);
		millis(properties.getConnectTimeout(), server::setConnectTimeout);
	}

	private static boolean isSsl(RedisProperties properties) {
		return properties.getSsl().isEnabled() || (properties.getUrl() != null && properties.getUrl().startsWith("rediss://"));
	}

	private static String address(String scheme, RedisConnectionDetails.Node node) {
		return scheme + host(node.host()) + ":" + node.port();
	}

	/** IPv6 는 대괄호로 감싼다 */
	private static String host(String host) {
		return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
	}

	private static String nodes(List<RedisConnectionDetails.Node> nodes) {
		return nodes.size() + " nodes";
	}

	private static void millis(Duration duration, java.util.function.IntConsumer setter) {
		if (duration != null) {
			setter.accept((int) Math.min(Integer.MAX_VALUE, duration.toMillis()));
		}
	}
}
