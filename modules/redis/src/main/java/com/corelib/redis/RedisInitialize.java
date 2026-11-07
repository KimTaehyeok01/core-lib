package com.corelib.redis;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import java.time.Duration;
import java.util.List;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * spring.data.redis 접속 키(host·url·sentinel.nodes·cluster.nodes 중 하나)가 있으면 켜진다 — 없으면 앱은 뜨고 RedisUtils 호출 시 '미초기화'.
 * <ul>
 *   <li>커넥션 팩토리는 Boot 자동설정 것을 쓴다. 이 클래스는 Boot 보다 먼저 돌아 redisTemplate·cacheManager 를 JSON 직렬화로 바꾼다</li>
 *   <li>기동 시 ping — Redis 에 닿지 않으면 기동 실패 (로컬 캐시로 대신 뜨지 않는다)</li>
 *   <li>소비 앱이 같은 이름(redisTemplate·cacheManager) 또는 RedissonClient 타입 빈을 만들면 core 것은 물러난다</li>
 * </ul>
 */
@AutoConfiguration(before = {RedisAutoConfiguration.class, CacheAutoConfiguration.class})
@Conditional(RedisInitialize.OnRedisConfiguredCondition.class)
@EnableConfigurationProperties({RedisProperty.class, RedisProperties.class})
@EnableCaching
public class RedisInitialize implements SmartInitializingSingleton {

	static final String TEMPLATE_BEAN = "redisTemplate";
	static final String CACHE_MANAGER_BEAN = "cacheManager";
	/** spring.data.redis.timeout 이 없을 때 Lettuce 명령 timeout — Boot 기본(60s)은 Redis 가 멈추면 요청 스레드를 너무 오래 잡는다 */
	static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(10);

	private final RedisProperty property;
	private final BeanFactory beanFactory;

	public RedisInitialize(RedisProperty property, BeanFactory beanFactory) {
		property.validate();
		this.property = property;
		this.beanFactory = beanFactory;
	}

	@Bean
	GenericJackson2JsonRedisSerializer coreRedisValueSerializer() {
		return RedisJson.serializer(property.getTrustedPackages());
	}

	@Bean(TEMPLATE_BEAN)
	@ConditionalOnMissingBean(name = TEMPLATE_BEAN)
	RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory, GenericJackson2JsonRedisSerializer coreRedisValueSerializer) {
		RedisTemplate<String, Object> template = new RedisTemplate<>();
		template.setConnectionFactory(connectionFactory);
		template.setKeySerializer(StringRedisSerializer.UTF_8);
		template.setHashKeySerializer(StringRedisSerializer.UTF_8);
		template.setValueSerializer(coreRedisValueSerializer);
		template.setHashValueSerializer(coreRedisValueSerializer);
		return template;
	}

	/**
	 * 캐시 키는 "{prefix}:{cacheName}::{key}". TTL 등 캐시별 정책은 RedisCacheManagerBuilderCustomizer 빈으로.
	 * <p>⚠️ <b>CacheErrorHandler 미등록</b> — Redis 장애 시 {@code @Cacheable} 이 폴백 없이 CoreException 500 으로 전파되고,
	 * Lettuce 명령 timeout({@link #DEFAULT_COMMAND_TIMEOUT} 또는 {@code spring.data.redis.timeout}) 동안 요청 스레드를 점유한다.
	 * 소비 앱은 {@link RedisCacheManagerBuilderCustomizer} 로 {@code CacheErrorHandler} 를 교체해
	 * 폴백(원본 메서드 호출) 동작으로 바꿀 수 있다. 완전한 옵션은 향후 라이브러리 레벨 제공 예정.</p>
	 */
	@Bean(CACHE_MANAGER_BEAN)
	@ConditionalOnMissingBean(name = CACHE_MANAGER_BEAN)
	RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, GenericJackson2JsonRedisSerializer coreRedisValueSerializer,
		ObjectProvider<RedisCacheManagerBuilderCustomizer> customizers) {
		RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
																  .computePrefixWith(cacheName -> property.applyPrefix(cacheName) + "::")
																  .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer.UTF_8))
																  .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(coreRedisValueSerializer));
		RedisCacheManager.RedisCacheManagerBuilder builder = RedisCacheManager.builder(connectionFactory).cacheDefaults(defaults);
		customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
		return builder.build();
	}

	@Bean
	LettuceClientConfigurationBuilderCustomizer coreRedisCommandTimeout(Environment environment) {
		return builder -> {
			if (!environment.containsProperty("spring.data.redis.timeout")) {
				builder.commandTimeout(DEFAULT_COMMAND_TIMEOUT);
			}
		};
	}

	/** 분산 락용 — Lettuce 와 같은 spring.data.redis.* 대상. 만들 때 접속하므로 Redis 가 없으면 여기서도 기동 실패 */
	@Bean(destroyMethod = "shutdown")
	@ConditionalOnMissingBean(RedissonClient.class)
	RedissonClient redissonClient(RedisConnectionDetails connectionDetails, RedisProperties redisProperties) {
		try {
			return Redisson.create(RedissonConfigs.from(connectionDetails, redisProperties));
		} catch (RuntimeException e) {
			throw new CoreException(RedisError.UNAVAILABLE, "Redisson 연결 실패 - spring.data.redis.* 접속 정보와 Redis 기동 여부 확인", e);
		}
	}

	/** 모든 빈이 만들어진 뒤 ping 으로 연결을 확인하고 RedisUtils 를 연다 */
	@Override
	@SuppressWarnings("unchecked")
	public void afterSingletonsInstantiated() {
		RedisTemplate<String, Object> template = beanFactory.getBean(TEMPLATE_BEAN, RedisTemplate.class);
		try {
			template.execute(connection -> {
				connection.ping();
				return null;
			}, true);
		} catch (RuntimeException e) {
			throw new CoreException(RedisError.UNAVAILABLE, "Redis 연결 확인(ping) 실패 - spring.data.redis.* 접속 정보와 Redis 기동 여부 확인", e);
		}
		RedisUtils.initialize(template, property, RedisJson.plainMapper());
	}

	/**
	 * 접속 키 중 하나라도 값이 있으면 켠다 — Boot RedisProperties 는 host 기본값이 localhost 라 바인딩 결과가 아니라 설정 원문을 본다.
	 * <p>빈 값(host: "" / nodes: [""] 등)은 "미설정"이 아니라 "실수"로 판정해 기동 실패(500) 로 떨어뜨린다 —
	 * 모든 키가 미설정인 경우만 조용히 미등록(소비자가 Redis 를 안 쓰는 상태).</p>
	 */
	static class OnRedisConfiguredCondition extends SpringBootCondition {

		static final List<String> KEYS = List.of("spring.data.redis.host", "spring.data.redis.url", "spring.data.redis.sentinel.nodes",
			"spring.data.redis.cluster.nodes");

		@Override
		public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
			ConditionMessage.Builder message = ConditionMessage.forCondition("spring.data.redis 접속 키");
			String found = configuredKey(context.getEnvironment());
			return found != null
				? ConditionOutcome.match(message.found("property").items(found))
				: ConditionOutcome.noMatch(message.didNotFind("property", "properties").items(KEYS.toArray()));
		}

		/**
		 * 스칼라 키(host·url) 는 설정 원문을, nodes 키는 바인딩 결과를 본다.
		 * <ul>
		 *   <li>값이 있으면 그 키를 반환한다</li>
		 *   <li>키 자체가 선언되지 않았으면 다음 키로 넘어간다</li>
		 *   <li>키는 있는데 값이 비어 있거나(" " 포함) nodes 목록의 원소 중 하나라도 blank 면 CoreException 으로 기동 실패</li>
		 *   <li>모든 키가 미설정이면 null — 조건 불일치 로 bean 미등록</li>
		 * </ul>
		 * @return 값이 있는 첫 키, 없으면 null
		 */
		static String configuredKey(Environment environment) {
			Binder binder = Binder.get(environment);
			for (String key : KEYS) {
				if (key.endsWith(".nodes")) {
					List<String> nodes = binder.bind(key, Bindable.listOf(String.class)).orElse(null);
					if (nodes == null || nodes.isEmpty()) {
						continue;
					}
					for (String node : nodes) {
						if (StringUtils.isBlank(node)) {
							throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
								"spring.data.redis." + key.substring("spring.data.redis.".length()) + " 목록에 빈 원소가 있습니다 — 안 쓰는 노드는 지우세요.");
						}
					}
					return key;
				}
				if (!environment.containsProperty(key)) {
					continue;
				}
				String value = environment.getProperty(key);
				if (StringUtils.isBlank(value)) {
					throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
						"spring.data.redis." + key.substring("spring.data.redis.".length()) + " 가 빈 문자열입니다 — 안 쓰는 키는 지우세요.");
				}
				return key;
			}
			return null;
		}
	}
}
