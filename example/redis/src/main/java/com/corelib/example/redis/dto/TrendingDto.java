package com.corelib.example.redis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 인기 검색어 전송 객체 */
public class TrendingDto {

	private TrendingDto() {
	}

	/** 검색 기록 요청 */
	public record RecordRequest(@NotBlank @Size(max = 100) String keyword) {
	}

	/** 상위 N개 응답 항목 — 검색어와 점수(누적 카운트) */
	public record Entry(String keyword, double score) {
	}

	/** 상위 N개 응답 */
	public record Top(List<Entry> items) {
	}
}
