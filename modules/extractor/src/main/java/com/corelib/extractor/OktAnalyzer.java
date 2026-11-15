package com.corelib.extractor;

import com.corelib.common.exception.CoreException;
import java.util.ArrayList;
import java.util.List;
import org.openkoreantext.processor.KoreanTokenJava;
import org.openkoreantext.processor.OpenKoreanTextProcessorJava;
import scala.collection.Seq;

/**
 * OpenKoreanTextProcessorJava 래퍼 — tokenize/normalize 결과를 corelib 타입({@link KeywordExtractorDto.Token})으로 변환한다.
 * OpenKoreanTextProcessorJava 는 상태를 갖지 않으므로 이 클래스도 상태가 없고 스레드 안전하다.
 */
final class OktAnalyzer {

	private OktAnalyzer() {
	}

	/** OKT normalize — 사전 기반 축약(예: "씨발ㅋㅋㅋㅋㅋ" -> "씨발ㅋㅋㅋ"). 입력 길이가 변할 수 있다. */
	static String normalize(String text) {
		try {
			return OpenKoreanTextProcessorJava.normalize(text).toString();
		} catch (RuntimeException e) {
			throw new CoreException(ExtractorError.OKT_LOAD_FAILED, "OKT normalize 실패", e);
		}
	}

	/** OKT tokenize — 각 토큰의 품사·offset·length 를 포함한다. */
	static List<KeywordExtractorDto.Token> tokenize(String text) {
		try {
			Seq<org.openkoreantext.processor.tokenizer.KoreanTokenizer.KoreanToken> tokens = OpenKoreanTextProcessorJava.tokenize(text);
			List<KoreanTokenJava> tokensInJava = OpenKoreanTextProcessorJava.tokensToJavaKoreanTokenList(tokens);
			List<KeywordExtractorDto.Token> result = new ArrayList<>(tokensInJava.size());
			for (KoreanTokenJava t : tokensInJava) {
				result.add(KeywordExtractorDto.Token.builder()
					.text(t.getText())
					.pos(t.getPos().name())
					.offset(t.getOffset())
					.length(t.getLength())
					.build());
			}
			return result;
		} catch (RuntimeException e) {
			throw new CoreException(ExtractorError.OKT_LOAD_FAILED, "OKT tokenize 실패", e);
		}
	}
}
