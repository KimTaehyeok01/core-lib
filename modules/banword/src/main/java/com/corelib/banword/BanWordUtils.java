package com.corelib.banword;

import com.corelib.common.exception.CoreException;
import com.corelib.extractor.KeywordExtractorDto;
import com.corelib.extractor.KeywordExtractorUtils;
import com.corelib.lang.StringUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.ahocorasick.trie.Emit;

/**
 * 금지어 검사 진입점.
 * <ul>
 *   <li>{@link #getBanWords(String)} — Aho-Corasick 부분 매칭만. OKT 미사용 — extractor 모듈 없이도 동작한다.</li>
 *   <li>{@link #getBanWordsWithKeywordExtractor(String)} — 형태소 토큰 경계 정렬까지. extractor 모듈이 classpath 에 있어야 한다.</li>
 * </ul>
 * 매칭과 토큰 분석은 같은 {@code normalized} 지역변수 하나를 입력으로 공유한다 — 좌표계 일치 보장(설계 핵심).
 *
 * <p>Emit.getEnd() 는 inclusive 라서 토큰 end(exclusive) 와 비교할 때 +1 보정이 필요하다.
 */
@RequiredArgsConstructor
public class BanWordUtils {

	private final BanWordPreprocessor preprocessor;
	private final BanWordMatcher matcher;
	private final BanWordProperty property;

	/**
	 * 부분 매칭 — 전처리 1~4 후 Aho-Corasick 매칭. 화이트리스트 Emit 구간에 포함되는 금지어는 제외한다.
	 * extractor 모듈 미설치 환경에서도 호출 가능.
	 *
	 * @throws CoreException {@link BanWordError#TEXT_TOO_LONG} — 입력이 설정된 최대 길이를 넘을 때
	 */
	public List<String> getBanWords(String text) {
		if (StringUtils.isBlank(text)) {
			return List.of();
		}
		enforceLength(text);
		String normalized = preprocessor.preprocessForDirectMatch(text);
		if (normalized.isEmpty()) {
			return List.of();
		}
		List<Emit> banEmits = matcher.findBanMatches(normalized);
		if (banEmits.isEmpty()) {
			return List.of();
		}
		List<Emit> whiteEmits = matcher.findWhiteMatches(normalized);

		List<String> result = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Emit be : banEmits) {
			if (isCoveredByWhite(be, whiteEmits)) {
				continue;
			}
			String original = matcher.toOriginal(be.getKeyword());
			if (seen.add(original)) {
				result.add(original);
			}
		}
		return result;
	}

	/**
	 * 형태소 토큰 경계 정렬 매칭 — 전처리 1~4 + OKT normalize 후, Emit 의 start/end 가 토큰 경계와 맞는 경우만 채택.
	 * 단 자음(ㄱ-ㆎ)만 또는 영문만으로 구성된 금지어는 토큰 경계 검사 없이 즉시 채택한다 (OKT 가 그런 문자열을 토큰으로 안 끊음).
	 *
	 * <p><b>클래스로더 주의</b>: 메서드 내부에서만 {@link KeywordExtractorUtils} 를 참조해, extractor 가 classpath 에 없으면
	 * {@link NoClassDefFoundError} 가 발생한다. 이 메서드는 그 에러를 잡아
	 * {@link BanWordError#EXTRACTOR_NOT_AVAILABLE} {@link CoreException} 으로 변환해 글로벌 핸들러를 거치게 한다.
	 *
	 * @throws CoreException {@link BanWordError#TEXT_TOO_LONG} — 입력이 설정된 최대 길이를 넘을 때,
	 *                       {@link BanWordError#EXTRACTOR_NOT_AVAILABLE} — extractor 모듈이 classpath 에 없을 때
	 */
	public List<String> getBanWordsWithKeywordExtractor(String text) {
		if (StringUtils.isBlank(text)) {
			return List.of();
		}
		enforceLength(text);
		// 좌표계 일치 가드: matcher + extractTokens 는 반드시 같은 normalized 지역변수를 입력으로 받는다
		String normalized;
		List<KeywordExtractorDto.Token> tokens;
		try {
			normalized = preprocessor.preprocessForTokenAlign(text);
			if (normalized.isEmpty()) {
				return List.of();
			}
			tokens = KeywordExtractorUtils.extractTokens(normalized);
		} catch (NoClassDefFoundError e) {
			throw new CoreException(BanWordError.EXTRACTOR_NOT_AVAILABLE, "extractor 모듈 미설치", e);
		}

		List<Emit> banEmits = matcher.findBanMatches(normalized);
		if (banEmits.isEmpty()) {
			return List.of();
		}
		List<Emit> whiteEmits = matcher.findWhiteMatches(normalized);

		Set<Integer> starts = new HashSet<>(tokens.size());
		Set<Integer> ends = new HashSet<>(tokens.size());
		for (KeywordExtractorDto.Token t : tokens) {
			starts.add(t.getOffset());
			ends.add(t.getOffset() + t.getLength());
		}
		List<String> result = new ArrayList<>();
		Set<String> seenResult = new LinkedHashSet<>();
		for (Emit be : banEmits) {
			if (isCoveredByWhite(be, whiteEmits)) {
				continue;
			}
			String keyword = be.getKeyword();
			boolean accepted;
			if (isConsonantOrAlpha(keyword)) {
				accepted = true;
			} else {
				// Emit.getEnd() 는 inclusive — +1 로 exclusive 비교
				accepted = starts.contains(be.getStart()) && ends.contains(be.getEnd() + 1);
			}
			if (!accepted) {
				continue;
			}
			String original = matcher.toOriginal(keyword);
			if (seenResult.add(original)) {
				result.add(original);
			}
		}
		return result;
	}

	private void enforceLength(String text) {
		if (text.length() > property.getMaxTextLength()) {
			throw new CoreException(BanWordError.TEXT_TOO_LONG,
				"length=" + text.length() + ", max=" + property.getMaxTextLength());
		}
	}

	/** 금지어 Emit 이 화이트 Emit 구간에 완전 포함되면 true. */
	private static boolean isCoveredByWhite(Emit ban, List<Emit> whiteEmits) {
		if (whiteEmits == null || whiteEmits.isEmpty()) {
			return false;
		}
		for (Emit we : whiteEmits) {
			if (we.getStart() <= ban.getStart() && ban.getEnd() <= we.getEnd()) {
				return true;
			}
		}
		return false;
	}

	/** 자음(ㄱ-ㆎ)만 또는 영문만으로 구성되면 true — OKT 가 토큰으로 안 끊는 케이스를 우회 채택한다. */
	private static boolean isConsonantOrAlpha(String s) {
		if (s == null || s.isEmpty()) {
			return false;
		}
		boolean allConsonant = true;
		boolean allAlpha = true;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < 'ㄱ' || c > 'ㆎ') {
				allConsonant = false;
			}
			if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
				allAlpha = false;
			}
			if (!allConsonant && !allAlpha) {
				return false;
			}
		}
		return allConsonant || allAlpha;
	}
}
