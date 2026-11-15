package com.corelib.extractor;

import java.util.Set;
import lombok.Builder;
import lombok.Getter;

public class KeywordExtractorDto {

	private KeywordExtractorDto() {
	}

	/** 형태소 분석기 종류 — 현재는 OKT 만 지원 */
	public enum Mode {
		OKT
	}

	/**
	 * 품사(part-of-speech) 분류 세트.
	 * <ul>
	 *   <li>{@link #BASIC} — Noun 만</li>
	 *   <li>{@link #ALL} — Noun·Verb·Adjective·Adverb·KoreanParticle·Alpha</li>
	 * </ul>
	 */
	public static final class Pos {

		/** 명사만 추출할 때 쓰는 기본 품사 집합 */
		public static final Set<String> BASIC = Set.of("Noun");

		/** 명사·동사·형용사·부사·조사·알파벳까지 포함한 확장 품사 집합 */
		public static final Set<String> ALL = Set.of("Noun", "Verb", "Adjective", "Adverb", "KoreanParticle", "Alpha");

		private Pos() {
		}
	}

	/** 키워드 추출 요청 DTO */
	@Getter
	@Builder
	public static class Request {

		/** 분석 대상 원문 */
		private final String text;

		/** 분석기 모드 (기본 OKT) */
		private final Mode mode;

		/** 추출 대상 품사 집합 (기본 {@link Pos#BASIC}) */
		private final Set<String> pos;
	}

	/** 형태소 단위 토큰 — OKT 결과를 직렬화한 값 */
	@Getter
	@Builder
	public static class Token {

		/** 토큰 문자열 */
		private final String text;

		/** 품사 (예: Noun, Verb, Adjective) */
		private final String pos;

		/** 원문 내 시작 오프셋 (0-base, inclusive) */
		private final int offset;

		/** 토큰의 char 길이 */
		private final int length;
	}
}
