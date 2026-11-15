package com.corelib.example.extractor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * extractor 예제 진입점.
 * <ul>
 *   <li>{@code POST /keywords/extract} — Noun/ALL 품사 키워드 추출</li>
 *   <li>{@code POST /keywords/tokens} — 토큰 리스트(offset/length 포함)</li>
 *   <li>{@code POST /keywords/normalize} — OKT 정규화</li>
 * </ul>
 */
@SpringBootApplication
public class ExtractorExampleApplication {

	public static void main(String[] args) {
		SpringApplication.run(ExtractorExampleApplication.class, args);
	}
}
