package com.corelib.redis;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import java.lang.reflect.Method;
import java.time.Duration;
import org.redisson.api.RedissonClient;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * &#64;RedisLock 어드바이저 — Redis 설정과 무관하게 항상 등록한다. Redis 가 없을 때 락 없이 조용히 통과하지 않고 500 이 나게 하려고.
 * &#64;Transactional 과 같은 Spring AOP 방식(AspectJ 불필요). order 는 트랜잭션(LOWEST_PRECEDENCE) 바로 바깥.
 */
@AutoConfiguration(after = RedisInitialize.class)
public class RedisLockInitialize {

	static final int ORDER = Ordered.LOWEST_PRECEDENCE - 1;

	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	static Advisor redisLockAdvisor(ObjectProvider<RedissonClient> redissonClient, ObjectProvider<RedisProperty> property, Environment environment) {
		DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(AnnotationMatchingPointcut.forMethodAnnotation(RedisLock.class),
			new RedisLockInterceptor(redissonClient, property, environment));
		advisor.setOrder(ORDER);
		return advisor;
	}

	/**
	 * 기동 시 모든 싱글톤 빈을 훑어 {@link RedisLock#waitTimeString()}·{@link RedisLock#leaseTimeString()} 를 사전에 해석·검증한다.
	 * 호출 시점이 아닌 기동 시점에 실패시켜 — 예: {@code waitTimeString = "abc"} 는 첫 요청이 아니라 기동 로그에 즉시 노출.
	 * <p>메서드에 직접 선언된 어노테이션만 검증한다 — 상속·메타 어노테이션 스캔은 과잉이라 생략.</p>
	 */
	@Bean
	static RedisLockTimeStringValidator redisLockTimeStringValidator(ListableBeanFactory beanFactory, Environment environment) {
		return new RedisLockTimeStringValidator(beanFactory, environment);
	}

	static final class RedisLockTimeStringValidator implements SmartInitializingSingleton {

		private final ListableBeanFactory beanFactory;
		private final Environment environment;

		RedisLockTimeStringValidator(ListableBeanFactory beanFactory, Environment environment) {
			this.beanFactory = beanFactory;
			this.environment = environment;
		}

		@Override
		public void afterSingletonsInstantiated() {
			for (String beanName : beanFactory.getBeanDefinitionNames()) {
				Class<?> type;
				try {
					type = beanFactory.getType(beanName, false);
				} catch (RuntimeException ignored) {
					continue;
				}
				if (type == null) {
					continue;
				}
				Class<?> userClass = ClassUtils.getUserClass(type);
				Method[] methods;
				try {
					methods = userClass.getDeclaredMethods();
				} catch (Throwable ignored) {
					continue;
				}
				for (Method method : methods) {
					RedisLock annotation = method.getAnnotation(RedisLock.class);
					if (annotation == null) {
						continue;
					}
					validate(annotation, method);
				}
			}
		}

		private void validate(RedisLock annotation, Method method) {
			String waitRaw = annotation.waitTimeString();
			if (!waitRaw.isBlank()) {
				try {
					DurationStringParser.resolveWait(waitRaw, environment, "waitTimeString");
				} catch (CoreException e) {
					throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
						"기동 중단 — " + name(method) + " @RedisLock waitTimeString: " + e.getMessage(), e);
				}
			}

			String leaseRaw = annotation.leaseTimeString();
			if (!leaseRaw.isBlank()) {
				try {
					DurationStringParser.resolveLease(leaseRaw, environment, "leaseTimeString");
				} catch (CoreException e) {
					throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
						"기동 중단 — " + name(method) + " @RedisLock leaseTimeString: " + e.getMessage(), e);
				}
			}
		}

		private static String name(Method method) {
			return method.getDeclaringClass().getSimpleName() + "." + method.getName();
		}
	}
}
