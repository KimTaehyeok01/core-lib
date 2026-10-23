package com.corelib.file;

import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractFileStorage implements FileStorage {

	private static final Logger log = LoggerFactory.getLogger(AbstractFileStorage.class);

	/** 서버에서 실행될 수 있는 확장자 - 허용 목록에 넣어도 항상 차단 (웹셸 업로드 방지) */
	private static final Set<String> ALWAYS_BLOCKED = Set.of(
		"jsp", "jspx", "jsw", "jsv", "jspf", "php", "php3", "php4", "php5", "phtml", "asp", "aspx", "asa", "cer",
		"cgi", "pl", "py", "sh", "bash", "bat", "cmd", "exe", "dll", "com", "msi", "jar", "war", "class");

	/** 브라우저에서 스크립트로 해석될 수 있는 확장자 - 허용 목록에 명시해야만 허용 (저장형 XSS 방지) */
	private static final Set<String> BLOCKED_UNLESS_ALLOWED = Set.of("html", "htm", "xhtml", "svg", "js", "mjs");

	private static final Pattern DIRECTORY_PATTERN = Pattern.compile("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*");
	private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

	protected final FileProperty property;

	protected AbstractFileStorage(FileProperty property) {
		this.property = property;
	}

	@Override
	public final FileDto.StoredFile upload(String directory, String originalName, InputStream content, long size,
										   String contentType) {
		final String extension = validate(originalName, content, size);
		final String key = buildKey(directory, extension);
		store(key, content, size, contentType);
		log.info("파일 업로드 완료 - key={}, size={}", key, size);
		return FileDto.StoredFile.builder()
								 .key(key)
								 .originalName(originalName)
								 .size(size)
								 .contentType(contentType)
								 .url(getUrl(key))
								 .build();
	}

	/** 검증과 키 생성이 끝난 파일을 실제 저장소에 기록 */
	protected abstract void store(String key, InputStream content, long size, String contentType);

	/** 다운로드/삭제/URL 용 키 검증 - 절대경로, 역슬래시, 상위 디렉터리(..) 차단 */
	protected static void validateKey(String key) {
		if (StringUtils.isBlank(key) || key.startsWith("/") || key.contains("\\")
				|| List.of(key.split("/")).contains("..")) {
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
		final long maxBytes = property.getMaxSize().toBytes();
		if (size > maxBytes) {
			throw new CoreException(FileError.INVALID_FILE,
				"파일 용량 초과 - 최대 " + property.getMaxSize().toMegabytes() + "MB");
		}

		final String extension = extensionOf(originalName);
		if (extension.isEmpty()) {
			throw new CoreException(FileError.INVALID_FILE, "확장자가 없는 파일은 업로드할 수 없습니다.");
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

	/** 마지막 점 뒤를 소문자로 (경로 구분자 이후 부분만 보므로 "a.php/b" 같은 이름도 안전) */
	static String extensionOf(String fileName) {
		final String name = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
		final int dot = name.lastIndexOf('.');
		return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT).trim();
	}
}
