// Swagger 문서 기본 정보 설정값 (core.swagger.*)
package com.corelib.swagger;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "core.swagger")
public class SwaggerProperty {

	/** 문서 제목 */
	private String title = "API";

	/** 문서 설명 */
	private String description = "";

	/** API 버전 */
	private String version = "v1";

	/** 모든 API 에 공통 에러 응답(ApiResponse 형식)을 붙이는 설정 */
	private final ErrorResponses errorResponses = new ErrorResponses();

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public ErrorResponses getErrorResponses() {
		return errorResponses;
	}

	/** core.swagger.error-responses.* */
	public static class ErrorResponses {

		/** false 면 공통 에러 응답을 붙이지 않는다 — 컨트롤러에 직접 적은 응답만 보인다 */
		private boolean enabled = true;

		/** 모든 API 에 붙일 상태코드 */
		private List<Integer> codes = new ArrayList<>(List.of(400, 404, 500));

		/** 인증이 필요한 API(@SecurityRequirement)에만 더 붙일 상태코드 — 공개 API 에는 붙이지 않는다 */
		private List<Integer> securedCodes = new ArrayList<>(List.of(401, 403));

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public List<Integer> getCodes() {
			return codes;
		}

		public void setCodes(List<Integer> codes) {
			this.codes = codes;
		}

		public List<Integer> getSecuredCodes() {
			return securedCodes;
		}

		public void setSecuredCodes(List<Integer> securedCodes) {
			this.securedCodes = securedCodes;
		}
	}
}
