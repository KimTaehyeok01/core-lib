package com.corelib.web;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 컨트롤러 안에서 난 권한 예외(@PreAuthorize 등)를 401·403 으로 — 없으면 전역 핸들러가 미처리 예외로 보고 500 을 낸다.
 * <ul>
 *   <li>로그인하지 않은(익명·인증 없음) 요청은 401 — 인증 필터가 {@link WebSecurityInitialize#AUTH_ERROR_ATTRIBUTE} 에 사유를 남겼으면 그 코드로</li>
 *   <li>로그인한 사용자의 권한 부족은 403</li>
 * </ul>
 * core 보안 체인과 별개로 등록된다(core.web.security.enabled=false 여도). 전역 핸들러보다 먼저 실행되도록 우선순위를 높인다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {

	/** 인증 실패 사유가 있는 401 에 붙이는 헤더 값 (RFC 6750) */
	static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

	private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
		if (TRUST_RESOLVER.isAuthenticated(SecurityContextHolder.getContext().getAuthentication())) {
			return response(CommonErrorCode.FORBIDDEN);
		}
		return unauthorized(request);
	}

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException e, HttpServletRequest request) {
		return unauthorized(request);
	}

	/** @return 인증 필터가 남긴 인증 실패 사유 — 없으면 null */
	static ErrorCode authError(HttpServletRequest request) {
		return request.getAttribute(WebSecurityInitialize.AUTH_ERROR_ATTRIBUTE) instanceof ErrorCode errorCode ? errorCode : null;
	}

	private static ResponseEntity<ApiResponse<Void>> unauthorized(HttpServletRequest request) {
		ErrorCode authError = authError(request);
		if (authError == null) {
			return response(CommonErrorCode.UNAUTHORIZED);
		}
		return ResponseEntity.status(authError.status())
							 .header(HttpHeaders.WWW_AUTHENTICATE, INVALID_TOKEN_CHALLENGE)
							 .body(ApiResponse.error(authError.code(), authError.message()));
	}

	private static ResponseEntity<ApiResponse<Void>> response(ErrorCode errorCode) {
		return ResponseEntity.status(errorCode.status()).body(ApiResponse.error(errorCode.code(), errorCode.message()));
	}
}
