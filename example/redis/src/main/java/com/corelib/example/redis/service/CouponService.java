package com.corelib.example.redis.service;

import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.example.redis.dto.CouponDto;
import com.corelib.redis.LockScope;
import com.corelib.redis.RedisLock;
import com.corelib.redis.RedisUtils;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 선착순 쿠폰 발급 서비스.
 * <ul>
 *   <li>재고 관리 — Redis {@link RedisUtils.Strings#decrement(String)} 로 원자적 차감.
 *       <b>재고 DECR 은 Redis atomic 이라 다중 인스턴스에서도 안전하다</b></li>
 *   <li>중복 방지 — {@link RedisLock @RedisLock}(scope={@link LockScope#GLOBAL GLOBAL}) 로 같은 (쿠폰, 멤버) 동시 요청을
 *       진입점(엔드포인트·@Async·배치) 무관하게 한 번에 하나로 묶는다</li>
 *   <li>멱등(⚠️ 데모 한정) — <b>발급 이력(issued) 은 JVM 메모리</b>. 재기동·다중 인스턴스에선 이력 미공유로
 *       같은 memberId 재발급이 가능하다. 실전은 Redis {@code Sets.add} 로 이력 이동 권장.</li>
 * </ul>
 * <p>샘플 재고는 {@code ApplicationReadyEvent} 에서 세팅한다 — {@code @PostConstruct} 는 Redis 가 아직 안 뜬 시점에 돌 수 있어 피했다.</p>
 */
@Slf4j
@Service
public class CouponService {

	private static final ErrorCode ALREADY_ISSUED = ErrorCode.of("COUPON_ALREADY_ISSUED", "이미 발급받은 쿠폰입니다.", 400);
	private static final ErrorCode SOLD_OUT = ErrorCode.of("COUPON_SOLD_OUT", "쿠폰 재고가 소진되었습니다.", 409);

	/** 발급 이력 — couponId → 발급받은 memberId 집합. 실서비스는 쿠폰 발급 테이블 */
	private final Map<Long, Set<Long>> issued = new ConcurrentHashMap<>();

	/** 기동 완료 후 샘플 쿠폰 재고 세팅 — 1 번 쿠폰 3 개, 2 번 쿠폰 1 개. setLong 으로 저장해야 이후 DECR 가 가능 */
	@EventListener(ApplicationReadyEvent.class)
	public void seedStock() {
		RedisUtils.Strings.setLong(stockKey(1L), 3L);
		RedisUtils.Strings.setLong(stockKey(2L), 1L);
		log.info("샘플 쿠폰 재고 세팅. 1번=3, 2번=1");
	}

	/**
	 * 쿠폰 발급 — (couponId, memberId) 조합 중복 요청을 모든 진입점에 걸쳐 차단한다.
	 * <ul>
	 *   <li>{@code scope = GLOBAL} — 같은 자원(쿠폰, 멤버) 배제. 다른 엔드포인트에서 들어와도 하나만 통과한다</li>
	 *   <li>재고는 Redis 에서 원자적으로 차감한 뒤 음수면 되돌린다 (경쟁 상태에서 과발급 방지)</li>
	 * </ul>
	 */
	@RedisLock(key = "#couponId + ':' + #memberId", scope = LockScope.GLOBAL, waitTime = 1)
	public CouponDto.IssueResponse issue(Long couponId, Long memberId) {
		Set<Long> couponIssued = issued.computeIfAbsent(couponId, id -> ConcurrentHashMap.newKeySet());
		if (couponIssued.contains(memberId)) {
			throw new CoreException(ALREADY_ISSUED).addErrorLogData("couponId", couponId).addErrorLogData("memberId", memberId);
		}

		long remaining = RedisUtils.Strings.decrement(stockKey(couponId));
		if (remaining < 0) {
			// 과차감 복구 — 음수가 된 재고를 되돌려 다른 요청의 판단이 흐려지지 않게
			RedisUtils.Strings.increment(stockKey(couponId));
			throw new CoreException(SOLD_OUT).addErrorLogData("couponId", couponId);
		}

		couponIssued.add(memberId);
		log.info("쿠폰 발급. couponId={}, memberId={}, remaining={}", couponId, memberId, remaining);
		return new CouponDto.IssueResponse(couponId, memberId, remaining);
	}

	private static String stockKey(Long couponId) {
		return "coupon:stock:" + couponId;
	}

	/** 테스트용 — 발급 이력 확인 */
	public boolean hasIssued(Long couponId, Long memberId) {
		Set<Long> couponIssued = issued.get(couponId);
		return couponIssued != null && couponIssued.contains(memberId);
	}
}
