package com.corelib.redis;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * core.redis.* — core 고유 설정만. 접속 정보는 표준 spring.data.redis.* (host·port·password·sentinel·cluster·ssl·timeout).
 */
@Getter
@Setter
@ConfigurationProperties("core.redis")
public class RedisProperty {

	/** 키 접두사 — RedisUtils 키·캐시 이름·락 키 앞에 "{prefix}:" 로 붙는다. 환경·서비스가 Redis 를 공유할 때 (예: prod:shop) */
	private String keyPrefix;

	/**
	 * 값(JSON)에서 다시 객체로 만들 수 있는 패키지 — java.lang·java.util·java.time 은 기본 허용.
	 * 직접 만든 DTO 를 저장·캐시하려면 그 패키지를 넣는다 (예: com.myapp). 목록에 없는 타입은 읽을 때 거부된다.
	 */
	private List<String> trustedPackages = new ArrayList<>();

	/** 락 운영 설정 — long-hold 경고 임계값 등 */
	private Lock lock = new Lock();

	/** 기동 시 설정 검사 */
	void validate() {
		if (keyPrefix != null && (StringUtils.isBlank(keyPrefix) || !keyPrefix.matches("[A-Za-z0-9:_.-]+") || keyPrefix.endsWith(":"))) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "core.redis.key-prefix 는 영문·숫자·: _ . - 만 쓰고 ':' 로 끝나지 않아야 합니다.");
		}
		for (String trusted : trustedPackages) {
			if (StringUtils.isBlank(trusted) || trusted.contains("*") || !trusted.matches("[A-Za-z0-9_.]+")) {
				throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "core.redis.trusted-packages 에는 패키지 이름만 넣습니다 (와일드카드 불가).");
			}
		}
		if (lock != null && lock.getLongHoldWarnThreshold() != null) {
			Duration threshold = lock.getLongHoldWarnThreshold();
			if (threshold.isNegative()) {
				throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "core.redis.lock.long-hold-warn-threshold 는 0 이상이어야 합니다.");
			}
			// 서브-ms(0 < t < 1ms) 는 scheduleAtFixedRate(period=0) IAE 와 의미 없는 과잉 경고 유발 — 0(off) 아니면 1ms 이상
			if (!threshold.isZero() && threshold.compareTo(Duration.ofMillis(1)) < 0) {
				throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
					"core.redis.lock.long-hold-warn-threshold 는 0(off) 또는 1ms 이상이어야 합니다.");
			}
		}
	}

	/** prefix 를 붙인 실제 키 */
	String applyPrefix(String key) {
		return keyPrefix == null ? key : keyPrefix + ":" + key;
	}

	/** 락 관련 운영 설정 */
	@Getter
	@Setter
	public static class Lock {

		/** 락 장기 보유 경고 임계값 — 임계값을 넘겨 락을 잡고 있으면 warn 로그. 0(또는 Duration.ZERO) 이면 끔. 기본 60s */
		private Duration longHoldWarnThreshold = Duration.ofSeconds(60);
	}
}
