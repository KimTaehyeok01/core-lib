package com.corelib.common.util;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.NoProviderFoundException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.Set;

/**
 * 요청 DTO 의 필드 어노테이션 검증.
 * <ul>
 *   <li>위반이 여러 개면 필드 경로 순으로 첫 위반만 메시지로 쓴다 — 응답이 매번 같아야 테스트·로그가 안정적이다</li>
 *   <li>거부된 값은 메시지에 넣지 않는다 — 카드번호·휴대폰 같은 입력값이 응답·로그로 새지 않게</li>
 * </ul>
 * @implNote 구현체(hibernate-validator)는 소비 앱의 spring-boot-starter-validation 이 제공한다
 */
public final class ValidateUtils {

	private static volatile Validator validator;

	private ValidateUtils() {
	}

	/**
	 * @throws CoreException BAD_REQUEST(대상 null, 어노테이션 위반) / INTERNAL_SERVER(검증 구현체 없음)
	 */
	public static void validate(Object target) {
		if (target == null) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "요청 값이 없습니다.");
		}
		Set<ConstraintViolation<Object>> violations = validator().validate(target);
		if (violations.isEmpty()) {
			return;
		}
		ConstraintViolation<Object> first = violations.stream()
													  .min(Comparator.comparing(v -> v.getPropertyPath().toString()))
													  .orElseThrow();
		throw new CoreException(CommonErrorCode.BAD_REQUEST, first.getPropertyPath() + " " + first.getMessage())
			.addErrorLogData("target", target.getClass().getSimpleName())
			.addErrorLogData("violationCount", violations.size());
	}

	/** 최초 호출 시 1회 생성 - Validator 는 스레드 안전하다 */
	private static Validator validator() {
		if (validator == null) {
			synchronized (ValidateUtils.class) {
				if (validator == null) {
					try {
						validator = Validation.buildDefaultValidatorFactory().getValidator();
					} catch (NoProviderFoundException e) {
						throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
							"Bean Validation 구현체 없음 - spring-boot-starter-validation 추가 필요", e);
					}
				}
			}
		}
		return validator;
	}
}
