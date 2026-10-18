// LocalFileStorage 업로드/다운로드/삭제와 보안 검증(경로 탈출·위험 확장자·용량) 검증
package com.corelib.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class LocalFileStorageTest {

	@TempDir
	Path baseDir;

	private FileProperty property;
	private LocalFileStorage storage;

	@BeforeEach
	void setUp() {
		property = new FileProperty();
		property.getLocal().setBaseDir(baseDir.toString());
		storage = new LocalFileStorage(property);
	}

	private FileDto.StoredFile upload(String directory, String name, String content) {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		return storage.upload(directory, name, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
	}

	private static ErrorCode errorCodeOf(Throwable e) {
		return ((CoreException) e).getErrorCode();
	}

	@Test
	@DisplayName("업로드 → 다운로드 → 삭제 - 키는 {디렉터리}/yyyy/MM/dd/{uuid}.{확장자}")
	void uploadDownloadDelete() throws Exception {
		FileDto.StoredFile stored = upload("board/notice", "공지.pdf", "hello");

		assertThat(stored.getKey()).matches("board/notice/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]{36}\\.pdf");
		assertThat(stored.getOriginalName()).isEqualTo("공지.pdf");
		assertThat(stored.getUrl()).isEqualTo("/files/" + stored.getKey());
		try (InputStream content = storage.download(stored.getKey())) {
			assertThat(new String(content.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
		}

		storage.delete(stored.getKey());

		assertThatThrownBy(() -> storage.download(stored.getKey()))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.NOT_FOUND);
	}

	@Test
	@DisplayName("MultipartFile 업로드")
	void uploadMultipart() {
		MockMultipartFile file = new MockMultipartFile("file", "profile.PNG", "image/png", new byte[] {1, 2, 3});

		FileDto.StoredFile stored = storage.upload("profile", file);

		assertThat(stored.getKey()).startsWith("profile/").endsWith(".png");
		assertThat(stored.getSize()).isEqualTo(3);
	}

	@ParameterizedTest
	@ValueSource(strings = {"shell.jsp", "SHELL.JSP", "a.php", "run.sh", "x.exe", "a.png.jsp"})
	@DisplayName("서버 실행 확장자는 대소문자·이중 확장자와 무관하게 차단")
	void blocksExecutableExtension(String name) {
		assertThatThrownBy(() -> upload("", name, "x"))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);
	}

	@Test
	@DisplayName("서버 실행 확장자는 허용 목록에 넣어도 차단")
	void blocksExecutableEvenIfAllowed() {
		property.setAllowedExtensions(List.of("jsp"));

		assertThatThrownBy(() -> upload("", "shell.jsp", "x"))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);
	}

	@Test
	@DisplayName("스크립트 확장자(svg 등)는 허용 목록에 명시해야만 허용")
	void scriptExtensionNeedsExplicitAllow() {
		assertThatThrownBy(() -> upload("", "logo.svg", "<svg/>"))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);

		property.setAllowedExtensions(List.of("svg"));

		assertThat(upload("", "logo.svg", "<svg/>").getKey()).endsWith(".svg");
	}

	@Test
	@DisplayName("허용 목록이 있으면 목록 밖 확장자 차단")
	void allowList() {
		property.setAllowedExtensions(List.of("png", "jpg"));

		assertThatThrownBy(() -> upload("", "doc.pdf", "x"))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);
	}

	@Test
	@DisplayName("용량 초과·빈 파일·확장자 없음 차단")
	void sizeAndName() {
		property.setMaxSize(DataSize.ofBytes(3));

		assertThatThrownBy(() -> upload("", "a.txt", "hello"))
			.extracting(LocalFileStorageTest::errorCodeOf).isEqualTo(FileError.INVALID_FILE);
		assertThatThrownBy(() -> storage.upload("", "a.txt", new ByteArrayInputStream(new byte[0]), 0, "text/plain"))
			.extracting(LocalFileStorageTest::errorCodeOf).isEqualTo(FileError.INVALID_FILE);
		assertThatThrownBy(() -> upload("", "README", "x"))
			.extracting(LocalFileStorageTest::errorCodeOf).isEqualTo(FileError.INVALID_FILE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"../etc", "a/../b", "a b", "a\\b", "한글"})
	@DisplayName("디렉터리명 경로 조작·허용 외 문자 차단")
	void blocksBadDirectory(String directory) {
		assertThatThrownBy(() -> upload(directory, "a.txt", "x"))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"../secret.txt", "a/../../secret.txt", "/etc/passwd", "a\\..\\b.txt", " "})
	@DisplayName("다운로드/삭제 키 경로 조작 차단 - baseDir 밖으로 못 나감")
	void blocksKeyTraversal(String key) {
		assertThatThrownBy(() -> storage.download(key))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);
		assertThatThrownBy(() -> storage.delete(key))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);
	}
}
