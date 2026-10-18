// Swagger 문서 기본 정보 설정값 (core.swagger.*)
package com.corelib.swagger;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "core.swagger")
public class SwaggerProperty {

	/** 문서 제목 */
	private String title = "API";

	/** 문서 설명 */
	private String description = "";

	/** API 버전 */
	private String version = "v1";

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
}
