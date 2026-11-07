package com.corelib.example.redis.service;

import com.corelib.example.redis.dto.TrendingDto;
import com.corelib.redis.RedisDto;
import com.corelib.redis.RedisUtils;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 인기 검색어 서비스 — ZSet 에 검색어별 점수를 누적한다.
 * <ul>
 *   <li>키 "trending" 은 {@code core.redis.key-prefix} 가 자동으로 붙어 "{redis-example}:trending" 으로 저장</li>
 *   <li>검색어가 들어올 때마다 {@link RedisUtils.ZSets#incrementScore(String, Object, double)} 로 +1</li>
 *   <li>상위 N 개는 점수 내림차순</li>
 * </ul>
 */
@Slf4j
@Service
public class TrendingService {

	private static final String KEY = "trending";

	/** 검색어 하나를 기록한다 — 같은 검색어가 들어올수록 점수가 올라간다 */
	public void record(String keyword) {
		double score = RedisUtils.ZSets.incrementScore(KEY, keyword, 1.0);
		log.debug("검색어 기록. keyword={}, score={}", keyword, score);
	}

	/** 점수 높은 순으로 상위 N 개 */
	public TrendingDto.Top top(int n) {
		List<RedisDto.ScoredValue> scored = RedisUtils.ZSets.top(KEY, n);
		List<TrendingDto.Entry> items = scored.stream()
											  .map(entry -> new TrendingDto.Entry(String.valueOf(entry.value()), entry.score()))
											  .toList();
		return new TrendingDto.Top(items);
	}
}
