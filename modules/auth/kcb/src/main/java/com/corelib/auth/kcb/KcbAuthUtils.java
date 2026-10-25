package com.corelib.auth.kcb;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.lang.StringUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * KCB 휴대폰 본인확인(OkCert3 팝업) 진입점.
 * <ol>
 *   <li>{@link #start} — 모듈 토큰(MDL_TKN) 발급. 브라우저는 {@code formFields()} 를 popupUrl 로 자동 POST 한다</li>
 *   <li>{@link #confirm} — return_url 로 온 mdl_tkn 으로 결과(이름·CI·DI 등)를 받는다. 토큰당 1회만 조회된다</li>
 * </ol>
 * <ul>
 *   <li>통신·암호화는 KCB 모듈(jar)이 한다 — 코어는 요청 조립·결과 검증·에러 변환만 맡는다</li>
 *   <li>이름·CI·DI·휴대폰번호는 로그에 남기지 않는다</li>
 *   <li>팝업 화면과 return_url 수신(컨트롤러)은 소비 프로젝트 책임이다</li>
 * </ul>
 * @implNote 결과 필드명(RSLT_NAME 등)은 공개 자료로 확정되지 않았다 — 계약 가이드와 다르면 {@code ConfirmResponse.getRaw()} 로 꺼내 쓴다
 */
@Slf4j
public final class KcbAuthUtils {

	private static final String SVC_POPUP_START = "IDS_HS_POPUP_START";
	private static final String SVC_POPUP_RESULT = "IDS_HS_POPUP_RESULT";
	/** 성공 코드 — 공개 래퍼 README 는 소문자 b000 로도 적어 대소문자를 무시한다 */
	private static final String SUCCESS_CODE = "B000";
	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	/** 모듈 요청·응답 JSON 전용 — 소비 앱 ObjectMapper 설정과 분리한다 */
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	/** 설정·호출기를 한 번에 바꾼다 — 호출 중인 스레드가 섞인 조합을 보지 않게 */
	private static volatile Config config;

	private KcbAuthUtils() {
	}

	/** 기동 시 {@link KcbAuthInitialize} 가 1회 호출한다 — 소비자가 직접 부르지 않는다 */
	static void initialize(KcbAuthProperty initializeKcbAuthProperty, KcbOkCertCaller initializeOkCertCaller) {
		config = new Config(initializeKcbAuthProperty, initializeOkCertCaller);
	}

	/**
	 * 팝업을 시작한다 — 모듈 토큰을 받아 팝업 form 값을 돌려준다.
	 *
	 * @param request 요청 정보. null 이면 설정값만으로 요청한다
	 * @throws CoreException BAD_REQUEST(returnUrl 형식·길이) / KCB_{RSLT_CD}(벤더 거절) / MODULE_NOT_FOUND(jar 없음) /
	 *                       EXTERNAL_ERROR(통신 실패·auth.kcb.timeout 초과)
	 */
	public static KcbAuthDto.StartResponse start(KcbAuthDto.StartRequest request) {
		Config current = config();
		KcbAuthProperty property = current.property();
		KcbAuthDto.StartRequest safeRequest = request == null ? KcbAuthDto.StartRequest.builder().build() : request;
		ValidateUtils.validate(safeRequest);

		String returnUrl = StringUtils.defaultIfBlank(safeRequest.getReturnUrl(), property.getReturnUrl());
		// 모듈 호출 전에 막는다 — javascript: 등 다른 스킴이 KCB 를 거쳐 브라우저 이동 주소가 되지 않게.
		if (!KcbAuthProperty.isValidReturnUrl(returnUrl)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "returnUrl 은 http(s) 절대 URL 이고 250 byte 이하여야 합니다.");
		}
		KcbAuthDto.RequestCause cause = safeRequest.getRequestCause() != null ? safeRequest.getRequestCause() : property.getRequestCause();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("RETURN_URL", returnUrl.trim());
		body.put("SITE_NAME", property.getSiteName());
		body.put("SITE_URL", property.getSiteUrl());
		body.put("RQST_CAUS_CD", cause.getCode());

		// 공개 샘플은 시작 응답의 RSLT_CD 를 보지 않는다 — 있으면 확인하고, 없으면 토큰 유무로 판단한다.
		Map<String, Object> response = call(current, SVC_POPUP_START, body, false);
		String mdlTkn = stringOf(response, "MDL_TKN");
		if (StringUtils.isBlank(mdlTkn)) {
			throw new CoreException(KcbAuthError.EXTERNAL_ERROR, "KCB 모듈 토큰(MDL_TKN)이 없습니다.");
		}
		log.info("KCB 본인확인 팝업 시작");
		return KcbAuthDto.StartResponse.builder()
									   .popupUrl(property.getPopupUrl())
									   .cpCd(property.getCpCd())
									   .mdlTkn(mdlTkn)
									   .build();
	}

	/**
	 * 본인확인 결과를 받는다.
	 *
	 * @throws CoreException BAD_REQUEST / KCB_{RSLT_CD}(인증 실패·만료·재조회) / RESULT_INCOMPLETE(CI·DI 없음) /
	 *                       MODULE_NOT_FOUND / EXTERNAL_ERROR(통신 실패·auth.kcb.timeout 초과)
	 */
	public static KcbAuthDto.ConfirmResponse confirm(KcbAuthDto.ConfirmRequest request) {
		Config current = config();
		ValidateUtils.validate(request);

		Map<String, Object> response = call(current, SVC_POPUP_RESULT, Map.of("MDL_TKN", request.getMdlTkn()), true);
		if (StringUtils.isBlank(stringOf(response, "CI")) || StringUtils.isBlank(stringOf(response, "DI"))) {
			throw new CoreException(KcbAuthError.RESULT_INCOMPLETE);
		}
		log.info("KCB 본인확인 결과 확인 완료");
		return KcbAuthDto.ConfirmResponse.builder()
										 .name(stringOf(response, "RSLT_NAME"))
										 .birthday(stringOf(response, "RSLT_BIRTHDAY"))
										 .sexCd(stringOf(response, "RSLT_SEX_CD"))
										 .nationalCd(stringOf(response, "RSLT_NTV_FRNR_CD"))
										 .ci(stringOf(response, "CI"))
										 .di(stringOf(response, "DI"))
										 .telComCd(stringOf(response, "TEL_COM_CD"))
										 .telNo(stringOf(response, "TEL_NO"))
										 .raw(Collections.unmodifiableMap(response))
										 .build();
	}

	/** 모듈을 호출하고 결과코드를 확인한다 — requireCode 면 RSLT_CD 가 반드시 B000 이어야 한다 */
	private static Map<String, Object> call(Config current, String svcName, Map<String, ?> body, boolean requireCode) {
		KcbAuthProperty property = current.property();
		String requestJson;
		try {
			requestJson = JSON.writeValueAsString(body);
		} catch (JsonProcessingException e) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "KCB 요청 생성에 실패했습니다.");
		}

		String responseJson = current.okCertCaller().call(property.getTarget().name(), property.getCpCd(), svcName,
			property.getLicensePath(), requestJson);

		Map<String, Object> response;
		try {
			response = JSON.readValue(responseJson, MAP_TYPE);
		} catch (JsonProcessingException e) {
			// 응답 원문에 개인정보가 있을 수 있어 cause 를 남기지 않는다.
			throw new CoreException(KcbAuthError.EXTERNAL_ERROR, "KCB 모듈 응답을 해석할 수 없습니다.").addErrorLogData("svcName", svcName);
		}

		String code = stringOf(response, "RSLT_CD");
		boolean isSuccess = code != null && SUCCESS_CODE.equalsIgnoreCase(code.trim());
		if ((requireCode || code != null) && !isSuccess) {
			String message = StringUtils.defaultIfBlank(stringOf(response, "RSLT_MSG"), "본인확인 처리에 실패했습니다.");
			throw new CoreException(ErrorCode.of("KCB_" + StringUtils.defaultIfBlank(code, "UNKNOWN"), message, 400), message)
				.addErrorLogData("svcName", svcName);
		}
		return response;
	}

	private static String stringOf(Map<String, Object> map, String key) {
		Object value = map.get(key);
		return value == null ? null : String.valueOf(value);
	}

	/** 초기화를 확인한다 — cp-cd 미설정이면 자동설정이 꺼져 있어 여기서 드러난다 */
	private static Config config() {
		Config current = config;
		if (current == null) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "KcbAuthUtils 미초기화 - auth.kcb.cp-cd 설정 확인");
		}
		return current;
	}

	/** 기동 시 정해지는 호출 설정 — 불변 */
	private record Config(KcbAuthProperty property, KcbOkCertCaller okCertCaller) {
	}
}
