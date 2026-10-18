// FileUtils 로 업로드·다운로드·삭제하고 첨부 정보를 보관하는 예제 서비스
package com.corelib.example.file.service;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.example.file.dto.FileExampleDto;
import com.corelib.file.FileDto;
import com.corelib.file.FileUtils;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 첨부파일 예제 서비스.
 * 다운로드·삭제는 "우리가 발급한 키"만 받는다 — 사용자가 임의 키를 넣어 남의 파일에 접근하지 못하게.
 * 실서비스는 여기에 소유자·권한 확인을 더한다.
 */
@Service
public class FileExampleService {

	private static final String DIRECTORY = "example";

	/** 실서비스는 첨부 테이블 — 예제라 메모리에 둔다 */
	private final Map<String, FileExampleDto.FileInfo> files = new ConcurrentHashMap<>();

	/** 크기·확장자·경로 검증은 FileUtils 가 한다 (위반이면 FILE-INVALID 400) */
	public FileExampleDto.FileInfo upload(MultipartFile file) {
		FileDto.StoredFile stored = FileUtils.upload(DIRECTORY, file);
		FileExampleDto.FileInfo info = FileExampleDto.FileInfo.builder()
															  .key(stored.getKey())
															  .originalName(stored.getOriginalName())
															  .size(stored.getSize())
															  .contentType(stored.getContentType())
															  .uploadedAt(LocalDateTime.now())
															  .build();
		files.put(info.getKey(), info);
		return info;
	}

	public List<FileExampleDto.FileInfo> list() {
		return files.values().stream().sorted(Comparator.comparing(FileExampleDto.FileInfo::getUploadedAt).reversed()).toList();
	}

	public FileExampleDto.Download download(String key) {
		FileExampleDto.FileInfo info = find(key);
		return FileExampleDto.Download.builder().info(info).resource(new InputStreamResource(FileUtils.download(key))).build();
	}

	public void delete(FileExampleDto.DeleteRequest request) {
		ValidateUtils.validate(request);
		find(request.getKey());
		FileUtils.delete(request.getKey());
		files.remove(request.getKey());
	}

	private FileExampleDto.FileInfo find(String key) {
		FileExampleDto.FileInfo info = key == null ? null : files.get(key);
		if (info == null) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "첨부파일을 찾을 수 없습니다.");
		}
		return info;
	}
}
