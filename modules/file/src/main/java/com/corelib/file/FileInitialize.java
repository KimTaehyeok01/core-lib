// core.file.storage 값(local/s3)에 맞는 FileStorage 빈을 등록하고 FileUtils 를 초기화하는 자동설정
package com.corelib.file;

import com.corelib.lang.StringUtils;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@AutoConfiguration
@EnableConfigurationProperties(FileProperty.class)
public class FileInitialize {

	/** 기본값 — storage 미설정이면 로컬 저장소 */
	@Bean
	@ConditionalOnMissingBean(FileStorage.class)
	@ConditionalOnProperty(prefix = "core.file", name = "storage", havingValue = "local", matchIfMissing = true)
	public FileStorage localFileStorage(FileProperty property) {
		return new LocalFileStorage(property);
	}

	/** 모든 빈이 만들어진 뒤 FileUtils 에 저장소를 넘긴다 — 소비 앱이 직접 정의한 FileStorage 도 그대로 쓴다 */
	@Bean
	public SmartInitializingSingleton fileUtilsInitializer(ObjectProvider<FileStorage> fileStorage) {
		return () -> fileStorage.ifAvailable(FileUtils::initialize);
	}

	/** AWS SDK 가 classpath 에 있고 storage=s3 일 때만 로드 — SDK 가 없으면 이 클래스는 읽히지 않는다 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(S3Client.class)
	@ConditionalOnProperty(prefix = "core.file", name = "storage", havingValue = "s3")
	static class S3StorageConfiguration {

		/** 자격증명은 AWS 기본 체인(환경변수/프로필/IAM Role) — 키를 설정 파일에 두지 않는다 */
		@Bean
		@ConditionalOnMissingBean
		S3Client coreS3Client(FileProperty property) {
			FileProperty.S3 s3 = property.getS3();
			S3ClientBuilder builder = S3Client.builder().region(Region.of(s3.getRegion()));
			if (StringUtils.isNotBlank(s3.getEndpoint())) {
				builder.endpointOverride(URI.create(s3.getEndpoint())).forcePathStyle(true);
			}
			return builder.build();
		}

		@Bean
		@ConditionalOnMissingBean
		S3Presigner coreS3Presigner(FileProperty property) {
			FileProperty.S3 s3 = property.getS3();
			S3Presigner.Builder builder = S3Presigner.builder().region(Region.of(s3.getRegion()));
			if (StringUtils.isNotBlank(s3.getEndpoint())) {
				builder.endpointOverride(URI.create(s3.getEndpoint()))
					   .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
			}
			return builder.build();
		}

		@Bean
		@ConditionalOnMissingBean(FileStorage.class)
		FileStorage s3FileStorage(FileProperty property, S3Client s3Client, S3Presigner presigner) {
			return new S3FileStorage(property, s3Client, presigner);
		}
	}
}
