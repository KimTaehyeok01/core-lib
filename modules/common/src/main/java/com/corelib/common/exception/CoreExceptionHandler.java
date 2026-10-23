package com.corelib.common.exception;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CoreExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(CoreExceptionHandler.class);

	/** 의도된 예외 - 담긴 ErrorCode 의 코드/상태/메시지를 그대로 응답 */
	@ExceptionHandler(CoreException.class)
	public ResponseEntity<ApiResponse<Void>> handleCore(CoreException e) {
		ErrorCode errorCode = e.getErrorCode();
		if (e.getErrorLogData().isEmpty()) {
			log.warn("CoreException - code={}, message={}", errorCode.code(), e.getMessage());
		} else {
			log.warn("CoreException - code={}, message={}, data={}", errorCode.code(), e.getMessage(), e.getErrorLogData());
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
	 * Spring MVC 의 요청 오류(깨진 JSON·파라미터 누락·타입 불일치·405·415·정적 리소스 404 등)를 4xx 에러코드로 바꾼다.
	 * @return 4xx 가 아니면 null — 500 으로 처리
	 */
	private static ErrorCode clientErrorOf(Exception e) {
		int status;
		if (e instanceof ErrorResponse errorResponse) {
			status = errorResponse.getStatusCode().value();
		} else if (e instanceof HttpMessageNotReadableException || e instanceof TypeMismatchException) {
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
}
