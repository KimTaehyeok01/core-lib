package com.corelib.auth.nice;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.crypto.Base64Utils;
import com.corelib.lang.StringUtils;
import com.corelib.webclient.FailureType;
import com.corelib.webclient.WebClientUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * NICE 통합인증(휴대폰·금융인증서·아이핀·공동인증서) 진입점.
 * <ol>
 *   <li>{@link #verification} — 토큰 발급 + 표준창 URL 발급(벤더 2콜). 브라우저는 authUrl 을 window.open 으로 연다</li>
 *   <li>{@link #verificationConfirm} — return_url 로 온 web_transaction_id 로 결과 조회 → 무결성 검증 → 복호화</li>
 * </ol>
 * <ul>
 *   <li>무저장 — 두 단계 사이 상태는 봉인된 txNo 로만 오간다. 소비자는 txNo 를 1회 쓰고 버린다</li>
 *   <li>표준창 호출과 return_url 수신(컨트롤러)은 소비 프로젝트 책임이다</li>
 *   <li>이름·CI·DI·휴대폰번호는 로그에 남기지 않는다 — request_no, transaction_id 만 기록한다</li>
 * </ul>
 */
@Slf4j
public final class NiceAuthUtils {

	private static final String SUCCESS_CODE = "0000";
	private static final String TOKEN_PATH = "/ido/intc/v1.0/auth/token";
	private static final String URL_PATH = "/ido/intc/v1.0/auth/url";
	private static final String RESULT_PATH = "/ido/intc/v1.0/auth/result";

	/** 봉인·평문 해석 전용 — 소비 앱 ObjectMapper 설정(naming strategy 등)이 암호 포맷에 영향을 주지 않게 분리한다 */
	private static final ObjectMapper JSON = JsonMapper.builder()
													   .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
													   .build();

	/** WebClient·설정을 한 번에 바꾼다 — 호출 중인 스레드가 섞인 조합을 보지 않게 */
	private static volatile Config config;

	private NiceAuthUtils() {
	}

	/** 기동 시 {@link NiceAuthInitialize} 가 1회 호출한다 — 소비자가 직접 부르지 않는다 */
	static void initialize(WebClient initializeWebClient, NiceAuthProperty initializeNiceAuthProperty, Duration callTimeout) {
		config = new Config(initializeWebClient, initializeNiceAuthProperty, callTimeout);
	}

	/**
	 * 인증을 요청한다 — 토큰 발급과 표준창 URL 발급을 한 번에 처리한다.
	 *
	 * @param request 요청 정보. null 이면 설정값만으로 요청한다
	 * @return 표준창 URL 과 봉인된 거래번호(txNo)
	 * @throws CoreException BAD_REQUEST(요청값·URL 형식) / NICE_{result_code}(벤더 거절) / EXTERNAL_ERROR(통신)
	 */
	public static NiceAuthDto.VerificationResponse verification(NiceAuthDto.VerificationRequest request) {
		Config current = config();
		NiceAuthProperty property = current.property();
		NiceAuthDto.VerificationRequest safeRequest = request == null ? NiceAuthDto.VerificationRequest.builder().build() : request;
		ValidateUtils.validate(safeRequest);

		// 가이드: url 요청의 request_no 는 token 요청 값과 관계없어야 한다 — 소비자 지정값은 url(결과 조회까지 쓰는 쪽)에 쓴다.
		String tokenRequestNo = NiceAuthRequestSupport.newRequestNo();
		String authRequestNo = StringUtils.isBlank(safeRequest.getRequestNo())
			? NiceAuthRequestSupport.newRequestNo() : safeRequest.getRequestNo().trim();
		// 콜백 URL 은 토큰 발급(네트워크) 전에 검증한다 — 잘못된 요청으로 토큰을 낭비하지 않게.
		String returnUrl = NiceAuthRequestSupport.returnUrl(safeRequest.getReturnUrl(), property.getReturnUrl());
		String closeUrl = NiceAuthRequestSupport.closeUrl(safeRequest.getCloseUrl(), property.getCloseUrl());

		NiceAuthDto.TokenResponse token = requestToken(current, tokenRequestNo);
		NiceAuthDto.UrlResponse authUrl = requestAuthUrl(current, safeRequest, authRequestNo, returnUrl, closeUrl,
			token.getAccessToken());

		long issuedAt = System.currentTimeMillis();
		String txNo = NiceAuthTxSealer.seal(new NiceAuthTxSealer.TxState(token.getAccessToken(), token.getTicket(),
				token.getIterators(), authUrl.getTransactionId(), authRequestNo, issuedAt, token.getExpiresIn()),
			property.getClientSecret(), JSON);

		// 거래 유효시간과 토큰 만료 중 이른 쪽이 실제로 쓸 수 있는 마지막 시각이다.
		long expiresAt = Math.min(issuedAt + property.getTxTtl().toMillis(), token.getExpiresIn());
		log.info("NICE 인증 요청 발급. requestNo={}, transactionId={}", authRequestNo, authUrl.getTransactionId());

		return NiceAuthDto.VerificationResponse.builder()
											   .authUrl(authUrl.getAuthUrl())
											   .txNo(txNo)
											   .requestNo(authRequestNo)
											   .expiresAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(expiresAt), ZoneId.systemDefault()))
											   .build();
	}

	/**
	 * 인증 결과를 확인한다 — 거래번호 해제 → 결과 조회 → 무결성 검증 → 복호화 순으로 수행한다.
	 *
	 * @return 이름·생년월일·성별·내외국인·CI·DI·통신사·휴대폰번호 (계약된 항목만, 나머지는 null)
	 * @throws CoreException TX_INVALID·TX_EXPIRED(거래번호) / NICE_{result_code}(예: 3032 미인증·만료, 3033 기제공 건) /
	 *                       INTEGRITY_FAILED / DECRYPT_FAILED / RESULT_INCOMPLETE / EXTERNAL_ERROR
	 */
	public static NiceAuthDto.VerificationConfirmResponse verificationConfirm(NiceAuthDto.VerificationConfirmRequest request) {
		Config current = config();
		NiceAuthProperty property = current.property();
		ValidateUtils.validate(request);

		NiceAuthTxSealer.TxState tx = NiceAuthTxSealer.unseal(request.getTxNo(), property.getClientSecret(), property.getTxTtl(), JSON);
		NiceAuthDto.ResultResponse result = requestResult(current, tx, request.getWebTransactionId());

		// NICE 는 결과 요청에 쓴 access_token 의 ticket·iterators 로 암호화한다 — 봉인해 둔 같은 토큰 값을 쓴다.
		String keyString = NiceAuthCrypto.deriveKeyString(tx.ticket(), tx.transactionId(), tx.iterators());
		NiceAuthCrypto.verifyIntegrity(result.getEncData(), result.getIntegrityValue(), keyString);
		NiceAuthDto.ResultPlain plain = parsePlain(NiceAuthCrypto.decrypt(result.getEncData(), keyString), tx);

		log.info("NICE 인증 결과 확인 완료. requestNo={}, transactionId={}", tx.requestNo(), tx.transactionId());
		return NiceAuthDto.VerificationConfirmResponse.builder()
													  .name(plain.getName())
													  .birthdate(plain.getBirthdate())
													  .gender(plain.getGender())
													  .nationalInfo(plain.getNationalInfo())
													  .ci(plain.getCi())
													  .di(plain.getDi())
													  .mobileCo(plain.getMobileCo())
													  .mobileNo(plain.getMobileNo())
													  .build();
	}

	/**
	 * 접근 토큰을 발급받는다.
	 * @implNote Basic 값은 표준 base64 가 아니라 base64url(무패딩)이라 HttpHeaders.setBasicAuth 를 쓰지 않는다
	 */
	private static NiceAuthDto.TokenResponse requestToken(Config current, String requestNo) {
		NiceAuthProperty property = current.property();
		String credentials = Base64Utils.encodeUrl(
			(property.getClientId() + ":" + property.getClientSecret()).getBytes(StandardCharsets.UTF_8));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("grant_type", "client_credentials");
		body.put("request_no", requestNo);

		NiceAuthDto.TokenResponse response = post(current, TOKEN_PATH, headers -> headers.set(HttpHeaders.AUTHORIZATION, "Basic " + credentials),
			body, NiceAuthDto.TokenResponse.class, Map.of("requestNo", requestNo));
		if (StringUtils.isBlank(response.getAccessToken()) || StringUtils.isBlank(response.getTicket())
				|| response.getIterators() == null || response.getExpiresIn() == null) {
			throw new CoreException(NiceAuthError.EXTERNAL_ERROR, "NICE 토큰 응답 필드가 비어 있습니다.").addErrorLogData("requestNo", requestNo);
		}
		return response;
	}

	/** 표준창 URL 과 transaction_id 를 발급받는다 — transaction_id 는 결과 조회 키이자 키 유도 salt 다 */
	private static NiceAuthDto.UrlResponse requestAuthUrl(Config current, NiceAuthDto.VerificationRequest request,
														  String requestNo, String returnUrl, String closeUrl, String accessToken) {
		NiceAuthProperty property = current.property();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("request_no", requestNo);
		body.put("return_url", returnUrl);
		body.put("svc_types", NiceAuthRequestSupport.svcTypes(request.getSvcTypes(), property.getSvcTypes()));
		body.put("method_type", (request.getMethodType() != null ? request.getMethodType() : property.getMethodType()).name());
		if (closeUrl != null) {
			body.put("close_url", closeUrl);
		}
		List<NiceAuthDto.ExpMod> expMods = request.getExpMods();
		if (expMods != null && !expMods.isEmpty()) {
			body.put("exp_mods", expMods.stream().map(NiceAuthDto.ExpMod::getCode).distinct().toList());
		}

		NiceAuthDto.UrlResponse response = post(current, URL_PATH, headers -> headers.setBearerAuth(accessToken), body,
			NiceAuthDto.UrlResponse.class, Map.of("requestNo", requestNo));
		if (StringUtils.isBlank(response.getAuthUrl()) || StringUtils.isBlank(response.getTransactionId())) {
			throw new CoreException(NiceAuthError.EXTERNAL_ERROR, "NICE 표준창 응답 필드가 비어 있습니다.").addErrorLogData("requestNo", requestNo);
		}
		return response;
	}

	/** 인증 결과 암호문을 조회한다 — request_no 는 토큰이 아니라 표준창 발급에 쓴 값이어야 한다 */
	private static NiceAuthDto.ResultResponse requestResult(Config current, NiceAuthTxSealer.TxState tx,
															String webTransactionId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("web_transaction_id", webTransactionId);
		body.put("transaction_id", tx.transactionId());
		body.put("request_no", tx.requestNo());

		NiceAuthDto.ResultResponse response = post(current, RESULT_PATH, headers -> headers.setBearerAuth(tx.accessToken()), body,
			NiceAuthDto.ResultResponse.class, Map.of("requestNo", tx.requestNo(), "transactionId", tx.transactionId()));
		if (StringUtils.isBlank(response.getEncData()) || StringUtils.isBlank(response.getIntegrityValue())) {
			throw new CoreException(NiceAuthError.EXTERNAL_ERROR, "NICE 결과 응답 필드가 비어 있습니다.")
				.addErrorLogData("requestNo", tx.requestNo());
		}
		return response;
	}

	/**
	 * 벤더 API 를 호출하고 result_code 0000 을 확인한다.
	 * @implNote 1xxx 는 HTTP 400/500, 3xxx 는 HTTP 200 으로 온다 — 4xx 본문의 result_code 도 살린다.
	 *           본인인증은 금전 거래가 없어 결과불명도 재인증으로 복구된다 — 통신 오류는 EXTERNAL_ERROR 하나로 묶는다
	 */
	private static <T extends NiceAuthDto.VendorResponse> T post(Config current, String path, Consumer<HttpHeaders> authorization,
																	Map<String, Object> body, Class<T> responseType,
																	Map<String, ?> logData) {
		T response;
		try {
			response = WebClientUtils.post(current.webClient(), path, headers -> {
				headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
				headers.set("X-Intc-DevLang", StringUtils.defaultIfBlank(current.property().getDevLang(), NiceAuthProperty.DEFAULT_DEV_LANG));
				authorization.accept(headers);
			}, body, responseType).timeout(current.callTimeout()).block();
		} catch (RuntimeException e) {
			if (WebClientUtils.classify(e) == FailureType.REJECTED) {
				throw vendorError(e, responseType).addErrorLogData(logData);
			}
			throw new CoreException(NiceAuthError.EXTERNAL_ERROR, "본인인증 서버와 통신할 수 없습니다.", e).addErrorLogData(logData);
		}
		if (response == null) {
			throw new CoreException(NiceAuthError.EXTERNAL_ERROR, "NICE 응답이 비어 있습니다.").addErrorLogData(logData);
		}
		if (!SUCCESS_CODE.equals(response.getResultCode())) {
			throw rejected(response).addErrorLogData(logData);
		}
		return response;
	}

	/** 3xxx(서비스·흐름 오류)는 사용자 재시도 대상이라 400, 1xxx·2xxx(설정·게이트웨이)는 502 로 내보낸다 */
	private static CoreException rejected(NiceAuthDto.VendorResponse response) {
		String code = StringUtils.defaultIfBlank(response.getResultCode(), "UNKNOWN");
		String message = StringUtils.defaultIfBlank(response.getResultMessage(), "본인인증 처리에 실패했습니다.");
		int status = code.startsWith("3") ? 400 : 502;
		return new CoreException(ErrorCode.of("NICE_" + code, message, status), message);
	}

	private static CoreException vendorError(Throwable error, Class<? extends NiceAuthDto.VendorResponse> responseType) {
		WebClientResponseException clientError = WebClientUtils.findClientError(error).orElse(null);
		NiceAuthDto.VendorResponse body = null;
		if (clientError != null) {
			try {
				body = clientError.getResponseBodyAs(responseType);
			} catch (RuntimeException ignored) {
				// NICE 규격 본문이 아니다
			}
		}
		if (body == null || StringUtils.isBlank(body.getResultCode())) {
			return new CoreException(NiceAuthError.EXTERNAL_ERROR, "본인인증 서버 오류 응답입니다.")
				.addErrorLogData("httpStatus", clientError == null ? null : clientError.getStatusCode().value());
		}
		return rejected(body);
	}

	/** 평문 JSON 을 해석하고 필수 항목(CI·DI)을 확인한다 — 예외에 평문 조각이 실릴 수 있어 원인·값을 남기지 않는다 */
	static NiceAuthDto.ResultPlain parsePlain(String plainJson, NiceAuthTxSealer.TxState tx) {
		NiceAuthDto.ResultPlain plain;
		try {
			plain = JSON.readValue(plainJson, NiceAuthDto.ResultPlain.class);
		} catch (JsonProcessingException e) {
			throw new CoreException(NiceAuthError.DECRYPT_FAILED, "NICE 인증 결과를 해석할 수 없습니다.").addErrorLogData("requestNo", tx.requestNo());
		}
		if (plain == null || StringUtils.isBlank(plain.getCi()) || StringUtils.isBlank(plain.getDi())) {
			throw new CoreException(NiceAuthError.RESULT_INCOMPLETE).addErrorLogData("requestNo", tx.requestNo());
		}
		return plain;
	}

	/** 초기화를 확인한다 — client-id 미설정이면 자동설정이 꺼져 여기서 드러난다. 설정 검증은 기동 때 {@link NiceAuthInitialize} 가 했다 */
	private static Config config() {
		Config current = config;
		if (current == null) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "NiceAuthUtils 미초기화 - auth.nice.client-id 설정 확인");
		}
		return current;
	}

	/** 기동 시 정해지는 호출 설정 — 불변 */
	private record Config(WebClient webClient, NiceAuthProperty property, Duration callTimeout) {
	}
}
