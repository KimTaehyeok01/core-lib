package com.corelib.extractor;

import com.corelib.lang.StringUtils;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 키워드/토큰/정규화 정적 유틸 — OKT 사전을 끌고 들어오는 유일한 진입점.
 * <ul>
 *   <li>{@link #extractKeywords(KeywordExtractorDto.Request)} — 품사 필터링 후 중복 제거</li>
 *   <li>{@link #extractTokens(String)} — 전체 토큰(offset/length 포함)</li>
 *   <li>{@link #normalize(String)} — OKT normalize (길이 변동 가능)</li>
 * </ul>
 * 빈/null 입력은 각각 빈 Set/List/"" 를 돌려주고 OKT 를 호출하지 않는다.
 */
public final class KeywordExtractorUtils {

	private KeywordExtractorUtils() {
	}

	/**
	 * 요청의 pos 필터에 해당하는 토큰만 모아 중복 제거한 Set 을 돌려준다.
	 * pos 가 비어 있으면 {@link KeywordExtractorDto.Pos#BASIC}(Noun 만) 을 기본값으로 쓴다.
	 */
	public static Set<String> extractKeywords(KeywordExtractorDto.Request request) {
		Objects.requireNonNull(request, "request");
		String text = request.getText();
		if (StringUtils.isBlank(text)) {
			return Set.of();
		}
		Set<String> pos = request.getPos();
		Set<String> filter = (pos == null || pos.isEmpty()) ? KeywordExtractorDto.Pos.BASIC : pos;
		List<KeywordExtractorDto.Token> tokens = OktAnalyzer.tokenize(text);
		Set<String> result = new LinkedHashSet<>();
		for (KeywordExtractorDto.Token t : tokens) {
			if (filter.contains(t.getPos())) {
				result.add(t.getText());
			}
		}
		return result;
	}

	/** OKT 토큰 전체 (품사 필터 없음). blank 입력은 빈 리스트. */
	public static List<KeywordExtractorDto.Token> extractTokens(String text) {
		if (StringUtils.isBlank(text)) {
			return List.of();
		}
		return OktAnalyzer.tokenize(text);
	}

	/** OKT normalize — 사전 기반 축약. blank 입력은 "". */
	public static String normalize(String text) {
		if (StringUtils.isBlank(text)) {
			return "";
		}
		return OktAnalyzer.normalize(text);
	}
}
