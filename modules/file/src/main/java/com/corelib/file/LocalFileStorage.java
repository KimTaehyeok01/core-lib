package com.corelib.file;

import com.corelib.common.exception.CoreException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalFileStorage extends AbstractFileStorage {

	private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

	private final Path baseDir;
	private final String urlPrefix;

	public LocalFileStorage(FileProperty property) {
		super(property);
		this.baseDir = Paths.get(property.getLocal().getBaseDir()).toAbsolutePath().normalize();
		this.urlPrefix = property.getLocal().getUrlPrefix().replaceAll("/+$", "");
	}

	@Override
	protected void store(String key, InputStream content, long size, String contentType) {
		final Path target = resolve(key);
		try {
			Files.createDirectories(target.getParent());
			Files.copy(content, target);
		} catch (FileAlreadyExistsException e) {
			// 같은 키의 기존 파일 — 남의 파일이라 지우지 않는다
			throw new CoreException(FileError.STORAGE_ERROR, "로컬 파일 저장 실패 - key=" + key, e);
		} catch (IOException e) {
			deletePartial(key, target);
			throw new CoreException(FileError.STORAGE_ERROR, "로컬 파일 저장 실패 - key=" + key, e);
		} catch (RuntimeException e) {
			// 용량 초과(INVALID_FILE) 등으로 복사가 중단됐다 — 쓰다 만 파일을 남기지 않는다
			deletePartial(key, target);
			throw e;
		}
	}

	private static void deletePartial(String key, Path target) {
		try {
			Files.deleteIfExists(target);
		} catch (IOException e) {
			log.warn("쓰다 만 파일 삭제 실패 - key={}", key, e);
		}
	}

	@Override
	public InputStream download(String key) {
		final Path target = resolve(key);
		if (!Files.isRegularFile(target)) {
			throw new CoreException(FileError.NOT_FOUND, "파일 없음 - key=" + key);
		}
		try {
			return Files.newInputStream(target);
		} catch (IOException e) {
			throw new CoreException(FileError.STORAGE_ERROR, "로컬 파일 읽기 실패 - key=" + key, e);
		}
	}

	@Override
	public void delete(String key) {
		final Path target = resolve(key);
		try {
			Files.deleteIfExists(target);
		} catch (IOException e) {
			throw new CoreException(FileError.STORAGE_ERROR, "로컬 파일 삭제 실패 - key=" + key, e);
		}
	}

	@Override
	public String getUrl(String key) {
		validateKey(key);
		return urlPrefix + "/" + key;
	}

	/** 키를 실제 경로로 변환 - 정규화 후에도 baseDir 안인지 다시 확인(경로 탈출 이중 차단) */
	private Path resolve(String key) {
		validateKey(key);
		final Path target;
		try {
			target = baseDir.resolve(key).normalize();
		} catch (InvalidPathException e) {
			// NUL 등 OS 가 거부하는 문자가 섞인 키 — 500 이 아니라 400 으로
			throw new CoreException(FileError.INVALID_FILE, "허용되지 않은 파일 키", e);
		}
		if (!target.startsWith(baseDir)) {
			throw new CoreException(FileError.INVALID_FILE, "허용되지 않은 파일 경로");
		}
		return target;
	}
}
