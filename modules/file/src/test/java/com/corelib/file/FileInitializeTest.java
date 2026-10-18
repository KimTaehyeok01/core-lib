// FileInitialize 의 저장소 선택(local/s3)·사용자 빈 우선·FileUtils 초기화 검증
package com.corelib.file;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FileInitializeTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(FileInitialize.class));

	@Test
	@DisplayName("storage 미설정이면 로컬 저장소, FileUtils 도 같은 저장소로 초기화")
	void defaultsToLocal() {
		runner.withPropertyValues("core.file.local.url-prefix=/uploads")
			  .run(context -> {
				  assertThat(context.getBean(FileStorage.class)).isInstanceOf(LocalFileStorage.class);
				  assertThat(FileUtils.getUrl("a/b.png")).isEqualTo("/uploads/a/b.png");
			  });
	}

	@Test
	@DisplayName("storage=s3 + bucket 이면 S3 저장소")
	void s3Storage() {
		runner.withPropertyValues("core.file.storage=s3", "core.file.s3.bucket=my-bucket")
			  .run(context -> assertThat(context.getBean(FileStorage.class)).isInstanceOf(S3FileStorage.class));
	}

	@Test
	@DisplayName("storage=s3 인데 bucket 이 없으면 기동 실패 (설정 오류를 일찍 드러냄)")
	void s3WithoutBucketFails() {
		runner.withPropertyValues("core.file.storage=s3")
			  .run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("소비 앱이 FileStorage 를 정의하면 그쪽을 쓴다")
	void backsOffToUserStorage() {
		FileProperty property = new FileProperty();
		LocalFileStorage custom = new LocalFileStorage(property);

		runner.withBean(FileStorage.class, () -> custom)
			  .run(context -> {
				  assertThat(context).hasSingleBean(FileStorage.class);
				  assertThat(context.getBean(FileStorage.class)).isSameAs(custom);
			  });
	}
}
