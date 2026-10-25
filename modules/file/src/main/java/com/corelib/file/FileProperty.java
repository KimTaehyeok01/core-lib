package com.corelib.file;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** 파일 저장소 설정 — S3 자격증명은 설정에 두지 않고 AWS 기본 체인(환경변수/프로필/IAM Role)으로 받는다 */
@Getter
@Setter
@ConfigurationProperties("core.file")
public class FileProperty {

	/** 저장소 종류 (local / s3) */
	private StorageType storage = StorageType.LOCAL;

	/** 파일 1개 최대 크기 — 서블릿 multipart 한도(spring.servlet.multipart.max-file-size)와 맞춘다 */
	private DataSize maxSize = DataSize.ofMegabytes(10);

	/** 허용 확장자 — 비어 있으면 위험 확장자를 뺀 전부 허용 (기동 안내에 경고, 업로드 용도에 맞게 지정 권장) */
	private List<String> allowedExtensions = new ArrayList<>();

	private final Local local = new Local();

	private final S3 s3 = new S3();

	public enum StorageType {
		LOCAL, S3
	}

	@Getter
	@Setter
	public static class Local {

		/** 저장 루트 디렉터리 */
		private String baseDir = "./uploads";

		/** getUrl 이 붙이는 URL 접두사 — 정적 리소스 매핑(addResourceHandlers)은 소비 앱이 설정한다 */
		private String urlPrefix = "/files";
	}

	@Getter
	@Setter
	public static class S3 {

		/** 버킷 이름 */
		private String bucket;

		/** 리전 */
		private String region = "ap-northeast-2";

		/** 엔드포인트 — MinIO/LocalStack 용, 비우면 AWS 기본 */
		private String endpoint;

		/** presigned URL 유효시간 */
		private Duration presignedUrlExpiry = Duration.ofMinutes(10);
	}
}
