package com.corelib.file;

import com.corelib.common.exception.CoreException;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorage {

	/**
	 * 파일 업로드.
	 * @param directory 하위 디렉터리 (예: "profile", "board/notice") - 영문/숫자/-/_ 와 / 만 허용
	 * @throws CoreException INVALID_FILE(확장자/크기/이름) / STORAGE_ERROR
	 */
	FileDto.StoredFile upload(String directory, String originalName, InputStream content, long size, String contentType);

	/** 컨트롤러에서 받은 MultipartFile 그대로 업로드 */
	default FileDto.StoredFile upload(String directory, MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new CoreException(FileError.INVALID_FILE, "업로드 파일이 비어 있습니다.");
		}
		try (InputStream content = file.getInputStream()) {
			return upload(directory, file.getOriginalFilename(), content, file.getSize(), file.getContentType());
		} catch (IOException e) {
			throw new CoreException(FileError.STORAGE_ERROR, "업로드 파일 읽기 실패", e);
		}
	}

	/** 파일 내용 스트림 - 호출측이 닫아야 함 */
	InputStream download(String key);

	/** 파일 삭제 (없으면 무시) */
	void delete(String key);

	/** 접근 URL (S3 는 만료되는 presigned URL) */
	String getUrl(String key);
}
