package com.corelib.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3FileStorageTest {

	private final Map<String, byte[]> objects = new HashMap<>();
	private PutObjectRequest lastPut;

	/** 네트워크 없이 메모리에 저장하는 S3Client - 실제로 쓰는 3개 메서드만 구현 */
	private final S3Client fakeS3 = new S3Client() {
		@Override
		public String serviceName() {
			return "s3";
		}

		@Override
		public void close() {
		}

		@Override
		public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
			lastPut = request;
			try (InputStream content = body.contentStreamProvider().newStream()) {
				objects.put(request.key(), content.readAllBytes());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			return PutObjectResponse.builder().build();
		}

		@Override
		public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
			byte[] bytes = objects.get(request.key());
			if (bytes == null) {
				throw NoSuchKeyException.builder().message("no such key").build();
			}
			return new ResponseInputStream<>(GetObjectResponse.builder().build(),
				AbortableInputStream.create(new ByteArrayInputStream(bytes)));
		}

		@Override
		public DeleteObjectResponse deleteObject(DeleteObjectRequest request) {
			objects.remove(request.key());
			return DeleteObjectResponse.builder().build();
		}
	};

	/** presign 은 로컬 서명 계산이라 가짜 자격증명으로도 동작한다 */
	private final S3Presigner presigner = S3Presigner.builder()
		.region(Region.AP_NORTHEAST_2)
		.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
		.build();

	private S3FileStorage storage;

	@BeforeEach
	void setUp() {
		FileProperty property = new FileProperty();
		property.getS3().setBucket("my-bucket");
		storage = new S3FileStorage(property, fakeS3, presigner);
	}

	private static ErrorCode errorCodeOf(Throwable e) {
		return ((CoreException) e).getErrorCode();
	}

	@Test
	@DisplayName("업로드 - 버킷/키/MIME/길이로 putObject, 다운로드·삭제")
	void uploadDownloadDelete() throws Exception {
		byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);

		FileDto.StoredFile stored = storage.upload("docs", "a.pdf", new ByteArrayInputStream(bytes), bytes.length,
			"application/pdf");

		assertThat(lastPut.bucket()).isEqualTo("my-bucket");
		assertThat(lastPut.key()).isEqualTo(stored.getKey()).startsWith("docs/").endsWith(".pdf");
		assertThat(lastPut.contentType()).isEqualTo("application/pdf");
		assertThat(lastPut.contentLength()).isEqualTo(5L);
		try (InputStream content = storage.download(stored.getKey())) {
			assertThat(content.readAllBytes()).isEqualTo(bytes);
		}

		storage.delete(stored.getKey());

		assertThatThrownBy(() -> storage.download(stored.getKey()))
			.extracting(S3FileStorageTest::errorCodeOf)
			.isEqualTo(FileError.NOT_FOUND);
	}

	@Test
	@DisplayName("putObject Content-Type 은 확장자 기반 - a.png 를 text/html 로 보내도 image/png")
	void contentTypeFromExtension() {
		byte[] bytes = {1, 2, 3};

		FileDto.StoredFile stored = storage.upload("img", "a.png", new ByteArrayInputStream(bytes), bytes.length, "text/html");

		assertThat(lastPut.contentType()).isEqualTo("image/png");
		assertThat(stored.getContentType()).isEqualTo("image/png");
	}

	@Test
	@DisplayName("getUrl - 버킷·키가 담긴 서명된 presigned URL (10분), attachment 로 내려받게")
	void presignedUrl() {
		String url = storage.getUrl("docs/2026/10/18/a.pdf");

		assertThat(url).contains("my-bucket").contains("docs/2026/10/18/a.pdf")
					   .contains("X-Amz-Signature=").contains("X-Amz-Expires=600")
					   .contains("response-content-disposition=attachment");
	}

	@Test
	@DisplayName("키 경로 조작 차단")
	void blocksKeyTraversal() {
		assertThatThrownBy(() -> storage.download("../other-bucket/x"))
			.extracting(S3FileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);
	}

	@Test
	@DisplayName("버킷 미설정 - 생성 시점에 설정 오류")
	void requiresBucket() {
		assertThatThrownBy(() -> new S3FileStorage(new FileProperty(), fakeS3, presigner))
			.extracting(S3FileStorageTest::errorCodeOf)
			.isEqualTo(CommonErrorCode.INTERNAL_SERVER);
	}
}
