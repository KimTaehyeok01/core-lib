package com.corelib.example.banword;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * banword 예제 진입점 — InMemoryBanWordSource + Direct 캐시 모드.
 * <ul>
 *   <li>{@code POST /banwords/check} — 부분 매칭 (OKT 없이도 동작)</li>
 *   <li>{@code POST /banwords/check-with-tokens} — 형태소 토큰 경계 정렬</li>
 *   <li>{@code POST /banwords/evict} — 캐시(=로컬 Trie) 재구성 트리거</li>
 *   <li>{@code POST /banwords/admin/ban} — InMemorySource 에 금지어 추가 (ROLE_ADMIN 필요, 예제는 인증 없음)</li>
 *   <li>{@code POST /banwords/admin/white} — InMemorySource 에 화이트 추가 (ROLE_ADMIN 필요, 예제는 인증 없음)</li>
 * </ul>
 */
@SpringBootApplication
public class BanWordExampleApplication {

	public static void main(String[] args) {
		SpringApplication.run(BanWordExampleApplication.class, args);
	}
}
