package com.corelib.example.extractor.service;

import com.corelib.example.extractor.dto.KeywordDto;
import com.corelib.extractor.KeywordExtractorDto;
import com.corelib.extractor.KeywordExtractorUtils;
import java.util.Set;
import org.springframework.stereotype.Service;

/** extractor 유틸을 그대로 호출하는 얇은 서비스 — 상태 없음 */
@Service
public class KeywordService {

	/** 요청 파라미터로 품사 세트를 받아 키워드 추출. pos 가 비어 있으면 Noun 만. */
	public KeywordDto.Response extract(KeywordDto.ExtractRequest request) {
		Set<String> pos = (request.pos() == null || request.pos().isEmpty())
			? KeywordExtractorDto.Pos.BASIC
			: request.pos();
		KeywordExtractorDto.Request domain = KeywordExtractorDto.Request.builder()
			.text(request.text())
			.mode(KeywordExtractorDto.Mode.OKT)
			.pos(pos)
			.build();
		return new KeywordDto.Response(KeywordExtractorUtils.extractKeywords(domain));
	}

	public KeywordDto.TokenResponse tokens(KeywordDto.TextRequest request) {
		return new KeywordDto.TokenResponse(KeywordExtractorUtils.extractTokens(request.text()));
	}

	public KeywordDto.NormalizeResponse normalize(KeywordDto.TextRequest request) {
		return new KeywordDto.NormalizeResponse(KeywordExtractorUtils.normalize(request.text()));
	}
}
