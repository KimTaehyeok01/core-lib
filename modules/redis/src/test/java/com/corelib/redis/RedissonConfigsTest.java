package com.corelib.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

class RedissonConfigsTest {

	@Test
	void standalone_은_host_port_db_와_인증_timeout_을_옮긴다() {
		RedisProperties properties = new RedisProperties();
		properties.setTimeout(Duration.ofSeconds(2));
		properties.setConnectTimeout(Duration.ofMillis(1500));
		RedisConnectionDetails details = details(new RedisConnectionDetails.Standalone() {
			@Override
			public String getHost() {
				return "redis.internal";
			}

			@Override
			public int getPort() {
				return 6380;
			}

			@Override
			public int getDatabase() {
				return 2;
			}
		}, null, null);

		Config config = RedissonConfigs.from(details, properties);

		assertThat(config.useSingleServer().getAddress()).isEqualTo("redis://redis.internal:6380");
		assertThat(config.useSingleServer().getDatabase()).isEqualTo(2);
		assertThat(config.useSingleServer().getPassword()).isEqualTo("secret");
		assertThat(config.useSingleServer().getTimeout()).isEqualTo(2000);
		assertThat(config.useSingleServer().getConnectTimeout()).isEqualTo(1500);
		assertThat(RedissonConfigs.describe(details)).isEqualTo("standalone redis.internal:6380/2").doesNotContain("secret");
	}

	@Test
	void ssl_과_IPv6() {
		RedisProperties properties = new RedisProperties();
		properties.getSsl().setEnabled(true);
		RedisConnectionDetails details = details(RedisConnectionDetails.Standalone.of("::1", 6379), null, null);

		assertThat(RedissonConfigs.from(details, properties).useSingleServer().getAddress()).isEqualTo("rediss://[::1]:6379");
	}

	@Test
	void sentinel_은_마스터_노드_센티널_인증을_옮긴다() {
		RedisConnectionDetails.Sentinel sentinel = new RedisConnectionDetails.Sentinel() {
			@Override
			public int getDatabase() {
				return 0;
			}

			@Override
			public String getMaster() {
				return "mymaster";
			}

			@Override
			public List<RedisConnectionDetails.Node> getNodes() {
				return List.of(new RedisConnectionDetails.Node("10.0.0.1", 26379), new RedisConnectionDetails.Node("10.0.0.2", 26379));
			}

			@Override
			public String getUsername() {
				return null;
			}

			@Override
			public String getPassword() {
				return "sentinel-secret";
			}
		};

		Config config = RedissonConfigs.from(details(null, sentinel, null), new RedisProperties());

		assertThat(config.useSentinelServers().getMasterName()).isEqualTo("mymaster");
		assertThat(config.useSentinelServers().getSentinelAddresses()).containsExactly("redis://10.0.0.1:26379", "redis://10.0.0.2:26379");
		assertThat(config.useSentinelServers().getSentinelPassword()).isEqualTo("sentinel-secret");
		assertThat(config.useSentinelServers().getPassword()).isEqualTo("secret");
	}

	@Test
	void cluster_는_노드를_옮긴다() {
		RedisConnectionDetails.Cluster cluster = () -> List.of(new RedisConnectionDetails.Node("10.0.0.21", 6379),
			new RedisConnectionDetails.Node("10.0.0.22", 6379));

		Config config = RedissonConfigs.from(details(null, null, cluster), new RedisProperties());

		assertThat(config.useClusterServers().getNodeAddresses()).containsExactly("redis://10.0.0.21:6379", "redis://10.0.0.22:6379");
		assertThat(RedissonConfigs.describe(details(null, null, cluster))).isEqualTo("cluster 2 nodes");
	}

	private static RedisConnectionDetails details(RedisConnectionDetails.Standalone standalone, RedisConnectionDetails.Sentinel sentinel,
		RedisConnectionDetails.Cluster cluster) {
		return new RedisConnectionDetails() {
			@Override
			public String getPassword() {
				return "secret";
			}

			@Override
			public Standalone getStandalone() {
				return standalone;
			}

			@Override
			public Sentinel getSentinel() {
				return sentinel;
			}

			@Override
			public Cluster getCluster() {
				return cluster;
			}
		};
	}
}
