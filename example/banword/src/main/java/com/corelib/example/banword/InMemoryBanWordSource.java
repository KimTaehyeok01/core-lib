package com.corelib.example.banword;

import com.corelib.banword.BanWordSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * BanWordSource 참조 구현 — 메모리 리스트로 금지어/화이트리스트를 공급한다.
 * 예제 Controller 의 /admin/* 엔드포인트에서 동적으로 수정 → evict 효과를 데모한다.
 */
@Component
public class InMemoryBanWordSource implements BanWordSource {

	private final List<String> banWords = new CopyOnWriteArrayList<>(
		List.of("씨발", "ㅅㅂ", "fuck", "병신", "게이", "미친년"));
	private final List<String> whiteWords = new CopyOnWriteArrayList<>(
		List.of("네비게이션"));

	@Override
	public List<String> getBanWords() {
		return new ArrayList<>(banWords);
	}

	@Override
	public List<String> getWhiteWords() {
		return new ArrayList<>(whiteWords);
	}

	public void addBan(String word) {
		banWords.add(word);
	}

	public void addWhite(String word) {
		whiteWords.add(word);
	}
}
