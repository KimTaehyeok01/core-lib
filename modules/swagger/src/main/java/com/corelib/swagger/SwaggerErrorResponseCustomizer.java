package com.corelib.swagger;

import com.corelib.common.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

/**
 * 공통 에러 응답 자동 추가.
 * <ul>
 *   <li>codes(기본 400·404·500) — 모든 API</li>
 *   <li>secured-codes(기본 401·403) — @SecurityRequirement 가 붙은 API 만</li>
 *   <li>컨트롤러에 직접 적은 응답(@ApiResponse)이 있으면 그 코드는 건드리지 않는다</li>
 * </ul>
 */
public class SwaggerErrorResponseCustomizer implements OperationCustomizer {

	/** 공통 에러 본문 스키마 이름 — {@link SwaggerAutoConfiguration} 이 components 에 등록한다 */
	public static final String ERROR_SCHEMA = "CoreErrorResponse";
	private static final String JSON = org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

	private final SwaggerProperty.ErrorResponses property;

	public SwaggerErrorResponseCustomizer(SwaggerProperty.ErrorResponses property) {
		this.property = property;
	}

	@Override
	public Operation customize(Operation operation, HandlerMethod handlerMethod) {
		ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
		property.getCodes().forEach(status -> addIfAbsent(responses, status));
		if (isSecured(operation, handlerMethod)) {
			property.getSecuredCodes().forEach(status -> addIfAbsent(responses, status));
		}
		operation.setResponses(responses);
		return operation;
	}

	private static void addIfAbsent(ApiResponses responses, Integer status) {
		String key = String.valueOf(status);
		if (!responses.containsKey(key)) {
			responses.addApiResponse(key, errorResponse(status));
		}
	}

	/** CommonErrorCode 와 같은 코드·메시지로 예시를 만든다 — 실제 전역 핸들러 응답과 똑같다 */
	private static ApiResponse errorResponse(int status) {
		CommonErrorCode errorCode = Arrays.stream(CommonErrorCode.values())
										  .filter(code -> code.status() == status).findFirst().orElse(null);
		String code = errorCode == null ? "C" + status : errorCode.code();
		String message = errorCode == null ? "처리할 수 없는 요청입니다." : errorCode.message();

		Map<String, Object> example = new LinkedHashMap<>();
		example.put("success", false);
		example.put("data", null);
		example.put("code", code);
		example.put("message", message);
		return new ApiResponse()
			.description(code + " " + message)
			.content(new Content().addMediaType(JSON, new MediaType()
				.schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA))
				.example(example)));
	}

	/** 문서의 보안 요구 또는 메서드·클래스의 @SecurityRequirement 로 판단 */
	private static boolean isSecured(Operation operation, HandlerMethod handlerMethod) {
		if (operation.getSecurity() != null && !operation.getSecurity().isEmpty()) {
			return true;
		}
		if (handlerMethod == null) {
			return false;
		}
		return handlerMethod.hasMethodAnnotation(SecurityRequirement.class)
			|| handlerMethod.hasMethodAnnotation(SecurityRequirements.class)
			|| AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), SecurityRequirement.class)
			|| AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), SecurityRequirements.class);
	}
}
