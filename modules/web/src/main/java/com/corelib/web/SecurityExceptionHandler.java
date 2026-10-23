package com.corelib.web;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.response.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 컨트롤러 안에서 난 권한 예외(@PreAuthorize 등)를 403·401 로 — 없으면 전역 핸들러가 미처리 예외로 보고 500 을 낸다.
 * 전역 핸들러보다 먼저 실행되도록 우선순위를 높인다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
		return response(CommonErrorCode.FORBIDDEN);
	}

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException e) {
		return response(CommonErrorCode.UNAUTHORIZED);
	}

	private static ResponseEntity<ApiResponse<Void>> response(ErrorCode errorCode) {
		return ResponseEntity.status(errorCode.status()).body(ApiResponse.error(errorCode.code(), errorCode.message()));
	}
}
