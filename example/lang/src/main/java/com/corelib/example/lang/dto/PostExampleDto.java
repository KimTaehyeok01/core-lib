package com.corelib.example.lang.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 게시판 예제 데이터 전송 객체 */
public class PostExampleDto {

	// === 글 등록 요청 — 화면 입력값이라 공백·null·중복이 섞여 온다 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class CreateRequest {

		@NotBlank
		@Size(max = 200)
		private String title;

		@NotBlank
		@Size(max = 5000)
		private String content;

		/** 비우면 익명 */
		@Size(max = 30)
		private String authorName;

		/** 답글 알림용 — 화면에는 마스킹해서만 보인다 */
		@Size(max = 100)
		private String authorEmail;

		private List<String> tags;
	}

	// === 글 (저장 모델) — 실서비스는 게시글 테이블 ===
	@Getter
	@Builder(toBuilder = true)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Post {

		/** 공유 링크용 코드 — 순번 대신 추측 불가한 값 */
		private final String shareCode;

		private final String title;

		private final String content;

		private final String authorName;

		private final String authorEmail;

		private final List<String> tags;

		/** 삭제 PIN — 비회원 글 삭제용 */
		private final String deletePin;

		/** 삭제 PIN 틀린 횟수 — 상한에 닿으면 PIN 삭제를 잠근다 */
		private final int deleteFailCount;

		private final LocalDateTime createdAt;
	}

	// === 등록 응답 — 삭제 PIN 은 이때 한 번만 알려준다 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class CreateResponse {

		private final String shareCode;

		private final String deletePin;
	}

	// === 목록 항목 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class PostSummary {

		private final String shareCode;

		private final String title;

		/** 본문 앞부분 */
		private final String summary;

		private final String authorName;

		private final List<String> tags;

		private final LocalDateTime createdAt;
	}

	// === 상세 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class PostDetail {

		private final String shareCode;

		private final String title;

		private final String content;

		/** 홍*동 / 익명 */
		private final String authorName;

		/** us**@example.com */
		private final String maskedAuthorEmail;

		private final List<String> tags;

		private final LocalDateTime createdAt;
	}

	// === 삭제 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class DeleteRequest {

		@NotBlank
		private String shareCode;

		@NotBlank
		private String deletePin;
	}
}
