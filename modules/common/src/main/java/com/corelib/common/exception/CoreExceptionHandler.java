package com.corelib.common.exception;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.response.ApiResponse;
import com.corelib.lang.StringUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
public class CoreExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(CoreExceptionHandler.class);

	/** errorLogData 값 하나의 로그 길이 상한 */
	static final int LOG_VALUE_MAX_LENGTH = 200;

	/** 로그 줄을 끊거나 위조할 수 있는 제어문자 (C0·C1·줄/문단 구분자) */
	private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cc}\\u2028\\u2029]");

	/**
	 * 의도된 예외 - 담긴 ErrorCode 의 코드/상태로 응답한다.
	 * 4xx 는 상세 메시지(detail)를 그대로, 5xx 는 ErrorCode 기본 메시지(벤더 원문 포함)·detail 에 설정 키 등 내부 정보가 있을 수 있어
	 * 응답 메시지를 공통 INTERNAL_SERVER 메시지로 고정하고 원인은 서버 로그로만 남긴다. 코드/상태는 그대로 노출한다.
	 */
	@ExceptionHandler(CoreException.class)
	public ResponseEntity<ApiResponse<Void>> handleCore(CoreException e) {
		ErrorCode errorCode = e.getErrorCode();
		if (errorCode.status() >= 500) {
			log.error("CoreException - code={}, message={}, data={}", errorCode.code(), e.getMessage(), logData(e), e);
			return ResponseEntity.status(errorCode.status())
								  .body(ApiResponse.error(errorCode.code(), CommonErrorCode.INTERNAL_SERVER.message()));
		}
		if (e.getErrorLogData().isEmpty()) {
			log.warn("CoreException - code={}, message={}", errorCode.code(), e.getMessage());
		} else {
			log.warn("CoreException - code={}, message={}, data={}", errorCode.code(), e.getMessage(), logData(e));
		}
		return ResponseEntity.status(errorCode.status())
							  .body(ApiResponse.error(errorCode.code(), e.getMessage()));
	}

	/** 예상 못 한 예외 - 내부 메시지를 노출하지 않고 500 으로 통일, 원인은 서버 로그로만 남김 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
		ErrorCode clientError = clientErrorOf(e);
		if (clientError != null) {
			log.warn("요청 오류 - code={}, exception={}", clientError.code(), e.getClass().getSimpleName());
			return ResponseEntity.status(clientError.status())
								  .body(ApiResponse.error(clientError.code(), clientError.message()));
		}
		ErrorCode errorCode = CommonErrorCode.INTERNAL_SERVER;
		log.error("처리되지 않은 예외", e);
		return ResponseEntity.status(errorCode.status())
							  .body(ApiResponse.error(errorCode.code(), errorCode.message()));
	}

	/**
	 * Spring MVC 의 요청 오류(깨진 JSON·파라미터 누락·타입 불일치·깨진 multipart·405·413·415·정적 리소스 404 등)를 4xx 에러코드로 바꾼다.
	 * 업로드 크기 초과(MaxUploadSizeExceededException)는 Spring 이 ErrorResponse(413)로 주므로 C413 이 된다.
	 * @return 4xx 가 아니면 null — 500 으로 처리
	 */
	private static ErrorCode clientErrorOf(Exception e) {
		int status;
		if (e instanceof ErrorResponse errorResponse) {
			status = errorResponse.getStatusCode().value();
		} else if (e instanceof HttpMessageNotReadableException || e instanceof TypeMismatchException || e instanceof MultipartException) {
			status = 400;
		} else {
			return null;
		}
		return switch (status) {
			case 400 -> CommonErrorCode.BAD_REQUEST;
			case 401 -> CommonErrorCode.UNAUTHORIZED;
			case 403 -> CommonErrorCode.FORBIDDEN;
			case 404 -> CommonErrorCode.NOT_FOUND;
			default -> status >= 400 && status < 500 ? ErrorCode.of("C" + status, "처리할 수 없는 요청입니다.", status) : null;
		};
	}

	/** errorLogData 를 로그용으로 — 외부 값이 로그 줄을 위조하지 못하게 제어문자를 '_' 로 바꾸고 값마다 길이를 자른다 */
	static Map<String, String> logData(CoreException e) {
		Map<String, String> data = new LinkedHashMap<>();
		e.getErrorLogData().forEach((key, value) -> {
			String text = String.valueOf(value);
			String cut = StringUtils.truncate(text, LOG_VALUE_MAX_LENGTH);
			data.put(key, CONTROL_CHARS.matcher(cut).replaceAll("_") + (cut.length() < text.length() ? "…" : ""));
		});
		return data;
	}
}
