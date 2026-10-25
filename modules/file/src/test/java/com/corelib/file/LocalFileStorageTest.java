package com.corelib.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
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

	@ParameterizedTest
	@ValueSource(strings = {"x.txt:evil", "a.t\nxt", "a.txt\n", "a.txt\r\n", "a.png ", "a.p-g", "a.abcdefghijk", "a.한글"})
	@DisplayName("확장자는 영문·숫자 1~10자만 - NTFS ADS 콜론·개행·공백·기호·11자 이상·비 ASCII 차단")
	void blocksMalformedExtension(String name) {
		assertThatThrownBy(() -> upload("", name, "x"))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"a.phar", "a.pht", "a.php7", "a.php8", "a.shtml", "a.hta", "a.ps1", "a.psm1", "a.vbs", "a.vbe",
		"a.wsf", "a.scr", "a.jspa", "a.asmx", "a.ashx"})
	@DisplayName("추가 차단 확장자(phar·shtml·hta·ps1·vbs·wsf·scr·jspa·asmx 등) - 허용 목록에 넣어도 차단")
	void blocksExtendedExecutableExtension(String name) {
		assertThatThrownBy(() -> upload("", name, "x"))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);

		property.setAllowedExtensions(List.of(AbstractFileStorage.extensionOf(name)));

		assertThatThrownBy(() -> upload("", name, "x"))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"a.xht", "a.svgz"})
	@DisplayName("추가 스크립트 확장자(xht·svgz)는 허용 목록에 명시해야만 허용")
	void extendedScriptExtensionNeedsExplicitAllow(String name) {
		assertThatThrownBy(() -> upload("", name, "x"))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);

		property.setAllowedExtensions(List.of(AbstractFileStorage.extensionOf(name)));

		assertThat(upload("", name, "x").getContentType()).isEqualTo("application/octet-stream");
	}

	@Test
	@DisplayName("Content-Type 은 확장자로 서버가 정한다 - a.png 를 text/html 로 보내도 image/png, 모르는 확장자는 octet-stream")
	void contentTypeFromExtension() {
		byte[] bytes = {1, 2, 3};

		FileDto.StoredFile png = storage.upload("", "a.png", new ByteArrayInputStream(bytes), bytes.length, "text/html");
		FileDto.StoredFile unknown = storage.upload("", "a.dat", new ByteArrayInputStream(bytes), bytes.length, "text/html");
		FileDto.StoredFile multipart = storage.upload("", new MockMultipartFile("file", "b.PDF", "text/html", bytes));

		assertThat(png.getContentType()).isEqualTo("image/png");
		assertThat(unknown.getContentType()).isEqualTo("application/octet-stream");
		assertThat(multipart.getContentType()).isEqualTo("application/pdf");
	}

	@Test
	@DisplayName("허용한 svg·html 도 octet-stream 으로 저장 - 브라우저가 스크립트로 열지 않게")
	void allowedScriptExtensionIsOctetStream() {
		property.setAllowedExtensions(List.of("svg", "html"));

		assertThat(upload("", "logo.svg", "<svg/>").getContentType()).isEqualTo("application/octet-stream");
		assertThat(upload("", "page.html", "<html/>").getContentType()).isEqualTo("application/octet-stream");
	}

	@Test
	@DisplayName("크기를 속인 InputStream - 실제로 읽은 바이트가 max-size 를 넘으면 중단, 쓰다 만 파일 없음")
	void stopsWhenActualBytesExceedMaxSize() throws Exception {
		property.setMaxSize(DataSize.ofBytes(10));
		byte[] bytes = new byte[1000];

		assertThatThrownBy(() -> storage.upload("", "a.txt", new ByteArrayInputStream(bytes), 3, "text/plain"))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);
		try (Stream<Path> paths = Files.walk(baseDir)) {
			assertThat(paths.filter(Files::isRegularFile)).isEmpty();
		}
	}

	@Test
	@DisplayName("실제 바이트가 max-size 와 같으면 저장 - 경계값")
	void storesWhenActualBytesEqualMaxSize() throws Exception {
		property.setMaxSize(DataSize.ofBytes(5));

		FileDto.StoredFile stored = upload("", "a.txt", "hello");

		try (InputStream content = storage.download(stored.getKey())) {
			assertThat(content.readAllBytes()).hasSize(5);
		}
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

	@ParameterizedTest
	@ValueSource(strings = {"a\n2026/log", "line\rbreak", "a\tb", "u v", "u v", "ctl\u0001x"})
	@DisplayName("키에 제어문자(\\p{Cc} + 줄/문단 구분자) 차단 - 로그 위조·NTFS ADS 방지")
	void blocksControlCharsInKey(String key) {
		assertThatThrownBy(() -> storage.download(key))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"a\u0000b", "a<b", "a>b", "a|b", "a\"b", "a*b", "a?b", "x:evil"})
	@DisplayName("키에 NUL·Windows 금지 문자(<>|\"*?:) 차단 - InvalidPathException 도 INVALID_FILE 로 변환")
	void blocksWindowsForbiddenCharsInKey(String key) {
		assertThatThrownBy(() -> storage.download(key))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);
		assertThatThrownBy(() -> storage.delete(key))
			.extracting(LocalFileStorageTest::errorCodeOf)
			.isEqualTo(FileError.INVALID_FILE);
	}
}
