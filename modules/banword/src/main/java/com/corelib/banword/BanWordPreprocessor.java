package com.corelib.banword;

import com.corelib.extractor.KeywordExtractorUtils;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 입력 전처리 파이프라인 — 금지어 매칭 전 적용한다. 단계 순서는 의도적으로 고정이다.
 * <ol>
 *   <li>소문자화 (Locale.ROOT)</li>
 *   <li>릿스픽 치환 (숫자/기호 → 알파벳)</li>
 *   <li>공백 제거</li>
 *   <li>특수문자 필터 (한글/영문/자음·모음 외 제거)</li>
 *   <li>(형태소 분기 한정) OKT normalize</li>
 * </ol>
 * OKT normalize 를 마지막에 두는 이유. 1~4 는 결정적 치환으로 선후 자유지만,
 * normalize 는 사전 기반 축약(`씨발ㅋㅋㅋㅋ` → `씨발ㅋㅋㅋ`)이라 앞에 두면 1~4 가 그 결과에 다시 손을 대 좌표가 어긋난다.
 */
public class BanWordPreprocessor {

	/** 릿스픽 치환 맵 — 숫자/기호를 유사 알파벳으로 바꿔 우회를 차단한다. */
	private static final Map<Character, Character> LEETSPEAK = Map.of(
		'0', 'o',
		'1', 'i',
		'3', 'e',
		'4', 'a',
		'5', 's',
		'7', 't',
		'@', 'a',
		'$', 's'
	);

	/** 한글(완성형)·영문·자음·모음(ㄱ-ㆎ) 외 전부 제거. */
	private static final Pattern NON_TEXT = Pattern.compile("[^가-힣a-zA-Zㄱ-ㆎ]");

	/** 1~4 단계만 적용 — 부분 매칭용(OKT 불필요). */
	public String preprocessForDirectMatch(String raw) {
		if (raw == null || raw.isEmpty()) {
			return "";
		}
		return filterSpecial(removeSpace(leetspeak(lowercase(raw))));
	}

	/** 1~4 적용 후 OKT normalize 까지 — 형태소 경계 정렬용. */
	public String preprocessForTokenAlign(String raw) {
		String stage4 = preprocessForDirectMatch(raw);
		if (stage4.isEmpty()) {
			return "";
		}
		return KeywordExtractorUtils.normalize(stage4);
	}

	private static String lowercase(String s) {
		return s.toLowerCase(Locale.ROOT);
	}

	private static String leetspeak(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			Character replaced = LEETSPEAK.get(c);
			sb.append(replaced != null ? replaced : c);
		}
		return sb.toString();
	}

	private static String removeSpace(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (!Character.isWhitespace(c)) {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static String filterSpecial(String s) {
		return NON_TEXT.matcher(s).replaceAll("");
	}
}
