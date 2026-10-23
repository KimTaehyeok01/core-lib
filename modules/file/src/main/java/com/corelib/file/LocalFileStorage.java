package com.corelib.file;

import com.corelib.common.exception.CoreException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LocalFileStorage extends AbstractFileStorage {

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
		} catch (IOException e) {
			throw new CoreException(FileError.STORAGE_ERROR, "로컬 파일 저장 실패 - key=" + key, e);
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
		final Path target = baseDir.resolve(key).normalize();
		if (!target.startsWith(baseDir)) {
			throw new CoreException(FileError.INVALID_FILE, "허용되지 않은 파일 경로");
		}
		return target;
	}
}
