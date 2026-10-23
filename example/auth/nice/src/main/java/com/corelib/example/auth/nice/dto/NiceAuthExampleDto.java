package com.corelib.example.auth.nice.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** NICE 통합인증 예제 데이터 전송 객체 */
public class NiceAuthExampleDto {

	// === 인증 시작 결과 (서비스 → 컨트롤러) — txNo 는 세션에만 두고 화면에 주지 않는다 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class StartResult {

		private final String authUrl;

		private final String txNo;
	}

	// === 인증 시작 응답 (화면) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class StartResponse {

		/** 표준창 URL — window.open 으로 연다 */
		private final String authUrl;
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

		/** "0" 여성 / "1" 남성 */
		private final String gender;

		/** "0" 내국인 / "1" 외국인 */
		private final String nationalInfo;

		private final String mobileNo;

		private final LocalDateTime joinedAt;

		private final LocalDateTime lastVerifiedAt;
	}

	// === 본인인증 결과 (화면 표시용, 마스킹) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class VerifiedMember {

		private final Long memberId;

		/** true 신규 가입 / false 기존 회원(같은 CI) 로그인 */
		private final Boolean isNewMember;

		/** 홍*동 */
		private final String maskedName;

		/** 생년(yyyy) */
		private final Integer birthYear;

		/** 만 나이 */
		private final Integer age;

		private final String gender;

		private final String nationalInfo;

		/** 010-****-5678 */
		private final String maskedMobileNo;

		private final LocalDateTime joinedAt;
	}

	// === 회원 목록 (마스킹) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class MemberSummary {

		private final Long memberId;

		private final String maskedName;

		private final String maskedMobileNo;

		private final LocalDateTime joinedAt;

		private final LocalDateTime lastVerifiedAt;
	}
}
