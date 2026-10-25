package com.corelib.file;

import static java.util.Map.entry;

import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractFileStorage implements FileStorage {

	private static final Logger log = LoggerFactory.getLogger(AbstractFileStorage.class);

	/** 서버·OS 에서 실행될 수 있는 확장자 - 허용 목록에 넣어도 항상 차단 (웹셸·실행 파일 업로드 방지) */
	private static final Set<String> ALWAYS_BLOCKED = Set.of(
		"jsp", "jspx", "jsw", "jsv", "jspf", "jspa",
		"php", "php3", "php4", "php5", "php7", "php8", "phtml", "pht", "phtm", "phps", "phar",
		"asp", "aspx", "asa", "asax", "ascx", "asmx", "ashx", "axd", "cer", "cshtml", "vbhtml",
		"shtml", "shtm", "stm",
		"cgi", "pl", "py", "sh", "bash", "bat", "cmd", "exe", "dll", "com", "msi", "jar", "war", "class",
		"hta", "ps1", "psm1", "vbs", "vbe", "wsf", "wsh", "jse", "scr", "pif", "cpl", "msc", "reg");

	/** 브라우저에서 스크립트로 해석될 수 있는 확장자 - 허용 목록에 명시해야만 허용 (저장형 XSS 방지) */
	private static final Set<String> BLOCKED_UNLESS_ALLOWED = Set.of("html", "htm", "xhtml", "xht", "svg", "svgz", "js", "mjs");

	/** 확장자 형식 - 콜론(NTFS ADS)·개행·공백 등이 섞인 확장자를 막는다 */
	private static final Pattern EXTENSION_PATTERN = Pattern.compile("[a-z0-9]{1,10}");

	private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

	/** 저장 Content-Type - 브라우저가 실행하지 않는 형식만 매핑하고 나머지(html·svg 포함)는 application/octet-stream */
	private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
		entry("png", "image/png"),
		entry("jpg", "image/jpeg"),
		entry("jpeg", "image/jpeg"),
		entry("gif", "image/gif"),
		entry("webp", "image/webp"),
		entry("bmp", "image/bmp"),
		entry("pdf", "application/pdf"),
		entry("txt", "text/plain"),
		entry("csv", "text/csv"),
		entry("xls", "application/vnd.ms-excel"),
		entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
		entry("doc", "application/msword"),
		entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
		entry("ppt", "application/vnd.ms-powerpoint"),
		entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
		entry("zip", "application/zip"),
		entry("mp3", "audio/mpeg"),
		entry("mp4", "video/mp4"));

	private static final Pattern DIRECTORY_PATTERN = Pattern.compile("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*");
	private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

	/** 키에 섞이면 로그 위조·NUL 절단·NTFS ADS 등으로 이어질 수 있는 제어문자(C0·C1·줄/문단 구분자) */
	private static final Pattern KEY_CONTROL_CHARS = Pattern.compile("[\\p{Cc}\\u2028\\u2029]");
	/** Windows 파일시스템이 거부하는 문자 - 리눅스에서도 저장은 되지만 공유 시 깨지므로 공통 차단 (경로 구분자 '/' 는 제외) */
	private static final Pattern KEY_FORBIDDEN_CHARS = Pattern.compile("[<>:\"|?*]");

	protected final FileProperty property;

	protected AbstractFileStorage(FileProperty property) {
		this.property = property;
	}

	/** contentType 은 쓰지 않는다 — 저장·응답 Content-Type 은 확장자로 서버가 정한다 ({@link #contentTypeOf}) */
	@Override
	public final FileDto.StoredFile upload(String directory, String originalName, InputStream content, long size,
										   String contentType) {
		final String extension = validate(originalName, content, size);
		final String key = buildKey(directory, extension);
		final String storedContentType = contentTypeOf(extension);
		store(key, new SizeLimitedInputStream(content, property.getMaxSize().toBytes()), size, storedContentType);
		log.info("파일 업로드 완료 - key={}, size={}", key, size);
		return FileDto.StoredFile.builder()
								 .key(key)
								 .originalName(originalName)
								 .size(size)
								 .contentType(storedContentType)
								 .url(getUrl(key))
								 .build();
	}

	/**
	 * 검증과 키 생성이 끝난 파일을 실제 저장소에 기록.
	 * content 는 max-size 를 넘겨 읽으면 INVALID_FILE 을 던진다 — 구현체는 실패 시 쓰다 만 데이터를 남기지 않는다.
	 *
	 * @param contentType 확장자 기반 서버 매핑값
	 */
	protected abstract void store(String key, InputStream content, long size, String contentType);

	/** 다운로드/삭제/URL 용 키 검증 - 절대경로, 역슬래시, 상위 디렉터리(..), 제어문자·NUL·Windows 금지 문자 차단 */
	protected static void validateKey(String key) {
		if (StringUtils.isBlank(key) || key.startsWith("/") || key.contains("\\")
				|| List.of(key.split("/")).contains("..")
				|| KEY_CONTROL_CHARS.matcher(key).find()
				|| KEY_FORBIDDEN_CHARS.matcher(key).find()) {
			throw new CoreException(FileError.INVALID_FILE, "허용되지 않은 파일 키");
		}
	}

	private String validate(String originalName, InputStream content, long size) {
		if (content == null || size <= 0) {
			throw new CoreException(FileError.INVALID_FILE, "빈 파일은 업로드할 수 없습니다.");
		}
		if (StringUtils.isBlank(originalName)) {
			throw new CoreException(FileError.INVALID_FILE, "파일명이 없습니다.");
		}
		if (size > property.getMaxSize().toBytes()) {
			throw sizeExceeded(property);
		}

		final String extension = extensionOf(originalName);
		if (extension.isEmpty()) {
			throw new CoreException(FileError.INVALID_FILE, "확장자가 없는 파일은 업로드할 수 없습니다.");
		}
		if (!EXTENSION_PATTERN.matcher(extension).matches()) {
			throw new CoreException(FileError.INVALID_FILE, "확장자는 영문·숫자 1~10자만 허용합니다.");
		}
		if (ALWAYS_BLOCKED.contains(extension)) {
			throw new CoreException(FileError.INVALID_FILE, "업로드할 수 없는 확장자 - " + extension);
		}
		final List<String> allowed = property.getAllowedExtensions();
		final boolean isExplicitlyAllowed = allowed.stream().anyMatch(extension::equalsIgnoreCase);
		if (BLOCKED_UNLESS_ALLOWED.contains(extension) && !isExplicitlyAllowed) {
			throw new CoreException(FileError.INVALID_FILE, "업로드할 수 없는 확장자 - " + extension);
		}
		if (!allowed.isEmpty() && !isExplicitlyAllowed) {
			throw new CoreException(FileError.INVALID_FILE, "허용되지 않은 확장자 - " + extension);
		}
		return extension;
	}

	private static CoreException sizeExceeded(FileProperty property) {
		return new CoreException(FileError.INVALID_FILE, "파일 용량 초과 - 최대 " + property.getMaxSize().toMegabytes() + "MB");
	}

	/** {디렉터리}/yyyy/MM/dd/{uuid}.{확장자} - 원본 파일명은 키에 넣지 않음(경로 조작·중복 방지) */
	private String buildKey(String directory, String extension) {
		final String normalized = normalizeDirectory(directory);
		final String fileName = UUID.randomUUID() + "." + extension;
		final String datePath = LocalDate.now().format(DATE_PATH);
		return (normalized.isEmpty() ? "" : normalized + "/") + datePath + "/" + fileName;
	}

	private String normalizeDirectory(String directory) {
		if (StringUtils.isBlank(directory)) {
			return "";
		}
		final String trimmed = directory.trim().replaceAll("^/+|/+$", "");
		if (!DIRECTORY_PATTERN.matcher(trimmed).matches()) {
			throw new CoreException(FileError.INVALID_FILE, "디렉터리명은 영문/숫자/-/_ 와 / 만 사용할 수 있습니다.");
		}
		return trimmed;
	}

	/** 마지막 점 뒤를 소문자로 (경로 구분자 이후 부분만 보므로 "a.php/b" 같은 이름도 안전) - 형식 검사는 validate 가 한다 */
	static String extensionOf(String fileName) {
		final String name = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
		final int dot = name.lastIndexOf('.');
		return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	/** 확장자 기반 Content-Type — 클라이언트 신고값은 위조될 수 있어 쓰지 않는다 */
	static String contentTypeOf(String extension) {
		return CONTENT_TYPES.getOrDefault(extension, DEFAULT_CONTENT_TYPE);
	}

	/** 실제로 읽은 바이트를 세어 상한을 넘으면 INVALID_FILE — 호출측이 넘긴 size 를 믿지 않는다 */
	private final class SizeLimitedInputStream extends FilterInputStream {

		private final long limit;
		private long count;
		private long markedCount;

		private SizeLimitedInputStream(InputStream in, long limit) {
			super(in);
			this.limit = limit;
		}

		@Override
		public int read() throws IOException {
			final int value = super.read();
			if (value >= 0) {
				add(1);
			}
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			// 상한 + 1 byte 까지만 읽는다 — 초과 여부만 알면 되고 그 이상은 저장소에 쓰지 않는다
			final int read = super.read(buffer, offset, (int) Math.min(length, limit - count + 1));
			if (read > 0) {
				add(read);
			}
			return read;
		}

		/** S3 SDK 는 재시도할 때 mark/reset 으로 되감는다 — 센 바이트도 함께 되돌린다 */
		@Override
		public synchronized void mark(int readLimit) {
			super.mark(readLimit);
			markedCount = count;
		}

		@Override
		public synchronized void reset() throws IOException {
			super.reset();
			count = markedCount;
		}

		private void add(long read) {
			count += read;
			if (count > limit) {
				throw sizeExceeded(property);
			}
		}
	}
}
