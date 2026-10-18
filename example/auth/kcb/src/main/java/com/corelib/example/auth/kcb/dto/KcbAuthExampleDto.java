// KCB 본인확인 예제 요청·응답 데이터
package com.corelib.example.auth.kcb.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** KCB 본인확인 예제 데이터 전송 객체 */
public class KcbAuthExampleDto {

	// === 팝업 시작 응답 — 화면이 popupUrl 로 formFields 를 POST 한다 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class StartResponse {

		private final String popupUrl;

		/** tc, cp_cd, mdl_tkn, target_id */
		private final Map<String, String> formFields;
	}

	// === 회원 (저장 모델) — 실서비스는 회원 테이블, CI 원문 대신 해시를 키로 둔다 ===
	@Getter
	@Builder(toBuilder = true)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Member {

		private final Long memberId;

		/** sha256(CI) — 같은 사람 판별용 (중복가입 방지) */
		private final String ciHash;

		private final String name;

		private final LocalDate birthDt;

		/** M 남 / F 여 */
		private final String sexCd;

		/** L 내국인 / F 외국인 */
		private final String nationalCd;

		private final String telNo;

		private final LocalDateTime joinedAt;

		private final LocalDateTime lastVerifiedAt;
	}

	// === 본인확인 결과 (화면 표시용, 마스킹) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class VerifiedMember {

		private final Long memberId;

		/** true 신규 가입 / false 기존 회원(같은 CI) 로그인 */
		private final Boolean isNewMember;

		private final String maskedName;

		private final Integer birthYear;

		/** 만 나이 */
		private final Integer age;

		private final String sexCd;

		private final String nationalCd;

		private final String maskedTelNo;

		private final LocalDateTime joinedAt;
	}

	// === 회원 목록 (마스킹) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class MemberSummary {

		private final Long memberId;

		private final String maskedName;

		private final String maskedTelNo;

		private final LocalDateTime joinedAt;

		private final LocalDateTime lastVerifiedAt;
	}
}
