package com.corelib.auth.kcb;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** KCB 본인확인 데이터 전송 객체 */
public class KcbAuthDto {

	/** 팝업 form 의 tc 값 — KCB 샘플에 "변경불가" 로 고정된 값 */
	public static final String POPUP_TC = "kcb.oknm.online.safehscert.popup.cmd.P931_CertChoiceCmd";

	// === 팝업 시작 요청 (소비자 → 코어) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class StartRequest {

		/** 인증 후 mdl_tkn 을 받을 URL — 비우면 auth.kcb.return-url */
		@Size(max = 250)
		private String returnUrl;

		/** 요청 사유 — 비우면 auth.kcb.request-cause */
		private RequestCause requestCause;
	}

	// === 팝업 시작 결과 (코어 → 소비자) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class StartResponse {

		/** 팝업 form 을 POST 할 URL */
		private final String popupUrl;

		/** 회원사 코드 */
		private final String cpCd;

		/** 모듈 토큰 — 팝업 form 에 넣는다. 결과 확인에도 같은 값이 돌아온다 */
		private final String mdlTkn;

		/** 브라우저가 popupUrl 로 자동 POST 할 hidden 필드들 (tc, cp_cd, mdl_tkn, target_id) */
		public Map<String, String> formFields() {
			Map<String, String> fields = new LinkedHashMap<>();
			fields.put("tc", POPUP_TC);
			fields.put("cp_cd", cpCd);
			fields.put("mdl_tkn", mdlTkn);
			fields.put("target_id", "");
			return fields;
		}
	}

	// === 결과 확인 요청 (소비자 → 코어) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class ConfirmRequest {

		/** return_url 로 온 mdl_tkn (쿼리 파라미터) */
		@NotBlank
		private String mdlTkn;
	}

	// === 결과 확인 응답 (코어 → 소비자) — 전 필드 개인정보 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class ConfirmResponse {

		/** 이름 (RSLT_NAME) */
		private final String name;

		/** 생년월일 yyyyMMdd (RSLT_BIRTHDAY) */
		private final String birthday;

		/** 성별 (RSLT_SEX_CD: M 남 / F 여) */
		private final String sexCd;

		/** 내외국인 (RSLT_NTV_FRNR_CD: L 내국인 / F 외국인) */
		private final String nationalCd;

		/** CI (CI) */
		private final String ci;

		/** DI (DI) */
		private final String di;

		/** 통신사 (TEL_COM_CD) */
		private final String telComCd;

		/** 휴대폰번호 (TEL_NO) */
		private final String telNo;

		/** 모듈 응답 원본 — 계약 가이드의 필드명이 위 매핑과 다를 때 직접 꺼내 쓴다 */
		private final Map<String, Object> raw;

		/** 개인정보가 로그·예외 메시지로 새지 않도록 값은 출력하지 않는다 */
		@Override
		public String toString() {
			return "ConfirmResponse{masked}";
		}
	}

	/** 운영/테스트 대상 서버 (callOkCert 의 target) */
	public enum Target {
		PROD, TEST
	}

	/** 본인확인 요청 사유 (RQST_CAUS_CD) */
	@Getter
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public enum RequestCause {
		SIGN_UP("00"),
		ADULT_VERIFICATION("01"),
		MEMBER_UPDATE("02"),
		FIND_PASSWORD("03"),
		PURCHASE("04"),
		ETC("99");

		private final String code;
	}
}
