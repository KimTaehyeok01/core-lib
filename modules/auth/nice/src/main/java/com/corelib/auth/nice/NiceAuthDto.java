// NICE 통합인증 요청·응답 데이터 (소비자용 요청/응답 + 벤더 응답)
package com.corelib.auth.nice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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

/** NICE 통합인증 데이터 전송 객체 */
public class NiceAuthDto {

	// === 인증 요청 (소비자 → 코어) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class VerificationRequest {

		/** 요청번호 — 비우면 코어가 채번한다. 지정하면 20~50자 */
		@Size(min = 20, max = 50, message = "requestNo 는 20~50자여야 합니다.")
		private String requestNo;

		/** 인증 완료 후 돌아올 URL — 비우면 auth.nice.return-url */
		@Size(max = 250)
		private String returnUrl;

		/** 닫기 버튼 클릭 시 이동할 URL — 비우면 auth.nice.close-url, 둘 다 없으면 창만 닫힌다 */
		@Size(max = 250)
		private String closeUrl;

		/** 인증 수단 — 비우면 auth.nice.svc-types. 계약된 수단만 쓸 수 있다 */
		private List<SvcType> svcTypes;

		/** web_transaction_id 전달 방식 — 비우면 auth.nice.method-type */
		private MethodType methodType;

		/** 표준창 부가 옵션 */
		private List<ExpMod> expMods;
	}

	// === 인증 요청 결과 (코어 → 소비자) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class VerificationResponse {

		/** 표준창 URL — 브라우저에서 window.open 으로 그대로 연다 */
		private final String authUrl;

		/** 봉인된 거래번호 — 결과 확인에 그대로 넘긴다. 1회 사용 후 폐기 */
		private final String txNo;

		/** 표준창 발급에 쓴 요청번호 — 추적용 */
		private final String requestNo;

		/** txNo 를 쓸 수 있는 마지막 시각 (거래 유효 10분과 토큰 만료 중 이른 쪽) */
		private final LocalDateTime expiresAt;
	}

	// === 결과 확인 요청 (소비자 → 코어) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class VerificationConfirmRequest {

		/** verification() 이 돌려준 봉인 거래번호 */
		@NotBlank
		private String txNo;

		/** 표준창이 return_url 로 넘긴 web_transaction_id */
		@NotBlank
		@Size(max = 100)
		private String webTransactionId;
	}

	// === 결과 확인 응답 (코어 → 소비자) — 전 필드 개인정보 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class VerificationConfirmResponse {

		/** 이름 */
		private final String name;

		/** 생년월일 (yyyyMMdd) */
		private final String birthdate;

		/** 성별 ("0" 여성 / "1" 남성) */
		private final String gender;

		/** 내외국인 ("0" 내국인 / "1" 외국인) */
		private final String nationalInfo;

		/** CI — 기관 공통 연계정보, 회원 식별(중복가입 확인)에 쓴다 */
		private final String ci;

		/** DI — 사이트별 중복가입 확인정보 */
		private final String di;

		/** 통신사 ("1" SKT / "2" KT / "3" LGU+ / "5" SKT알뜰 / "6" KT알뜰 / "7" LGU+알뜰) — 휴대폰 인증만 */
		private final String mobileCo;

		/** 휴대폰번호 — 휴대폰 인증만 */
		private final String mobileNo;

		/** 개인정보가 로그·예외 메시지로 새지 않도록 값은 출력하지 않는다 */
		@Override
		public String toString() {
			return "VerificationConfirmResponse{masked}";
		}
	}

	// === 벤더 응답 공통 ===
	@Getter
	@Setter
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class VendorResponse {

		/** 결과 코드 (0000 = 성공) */
		@JsonProperty("result_code")
		private String resultCode;

		/** 결과 메시지 */
		@JsonProperty("result_message")
		private String resultMessage;
	}

	// === 토큰 발급 응답 ===
	@Getter
	@Setter
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class TokenResponse extends VendorResponse {

		/** 접근 토큰 — url/result 요청의 Bearer, 24시간 유효 */
		@JsonProperty("access_token")
		private String accessToken;

		/** 만료 시각 — 유효기간(초)이 아니라 epoch milliseconds 다 */
		@JsonProperty("expires_in")
		private Long expiresIn;

		/** 키 유도(PBKDF2) 반복 횟수 */
		@JsonProperty("iterators")
		private Integer iterators;

		/** 키 유도(PBKDF2) password */
		@JsonProperty("ticket")
		private String ticket;
	}

	// === 표준창 URL 발급 응답 ===
	@Getter
	@Setter
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class UrlResponse extends VendorResponse {

		/** 표준창 URL — 10분 유효 */
		@JsonProperty("auth_url")
		private String authUrl;

		/** 거래 ID — 결과 조회 키이자 키 유도 salt, 10분 유효 */
		@JsonProperty("transaction_id")
		private String transactionId;
	}

	// === 인증 결과 응답 (암호문) ===
	@Getter
	@Setter
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class ResultResponse extends VendorResponse {

		/** 암호화된 인증 결과 — base64url( IV(16) || 암호문 || GCM 태그(16) ) */
		@JsonProperty("enc_data")
		private String encData;

		/** 무결성 값 — base64url(HmacSHA256(enc_data 문자열)) */
		@JsonProperty("integrity_value")
		private String integrityValue;
	}

	// === 복호화된 인증 결과 (계약된 항목만 온다) ===
	@Getter
	@Setter
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class ResultPlain {

		private String name;

		private String birthdate;

		private String gender;

		@JsonProperty("national_info")
		private String nationalInfo;

		private String ci;

		private String di;

		@JsonProperty("mobile_co")
		private String mobileCo;

		@JsonProperty("mobile_no")
		private String mobileNo;

		@Override
		public String toString() {
			return "ResultPlain{masked}";
		}
	}

	/** 인증 수단 (svc_types) */
	@Getter
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public enum SvcType {
		/** 휴대폰 */
		M("M"),
		/** 금융인증서 */
		F("F"),
		/** 아이핀 */
		I("I"),
		/** 공동인증서 */
		U("U");

		private final String code;
	}

	/** web_transaction_id 를 return_url 로 넘기는 방식 (method_type) */
	public enum MethodType {
		/** 쿼리스트링 ?web_transaction_id= */
		GET,
		/** form POST 본문 */
		POST
	}

	/** 표준창 부가 옵션 (exp_mods) */
	@Getter
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public enum ExpMod {
		/** 닫기 버튼 표시 */
		CLOSE_BUTTON_ON("closeButtonOn");

		private final String code;
	}
}
