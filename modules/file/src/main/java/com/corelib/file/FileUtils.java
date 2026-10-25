package com.corelib.file;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import java.io.InputStream;
import org.springframework.web.multipart.MultipartFile;

/**
 * 파일 업로드/다운로드 정적 진입점 — core.file.storage 로 고른 저장소(로컬/S3)에 그대로 위임한다.
 * <ul>
 *   <li>빈 주입이 편한 곳은 {@link FileStorage} 를 직접 주입해도 된다 — 같은 객체다</li>
 *   <li>DB 에는 업로드 결과의 key 만 저장한다 — URL 은 저장소마다 형태·만료가 달라 필요할 때 {@link #getUrl} 로 만든다</li>
 *   <li>업로드 권한·소유자 확인은 소비 프로젝트 책임이다 — 여기서는 파일 자체(크기·확장자·경로)만 검증한다</li>
 * </ul>
 */
public final class FileUtils {

	/** 기동 스레드가 넣고 요청 스레드가 읽는다 — volatile 로 다른 스레드에도 바로 보이게 */
	private static volatile FileStorage fileStorage;

	private FileUtils() {
	}

	/** 기동 시 {@link FileInitialize} 가 1회 호출한다 — 소비자가 직접 부르지 않는다 */
	static void initialize(FileStorage initializeFileStorage) {
		fileStorage = initializeFileStorage;
	}

	/**
	 * 컨트롤러에서 받은 MultipartFile 을 업로드한다.
	 *
	 * @param directory 하위 디렉터리 (예: "profile", "board/notice")
	 * @throws CoreException INVALID_FILE(빈 파일·크기·확장자·디렉터리명) / STORAGE_ERROR
	 */
	public static FileDto.StoredFile upload(String directory, MultipartFile file) {
		return storage().upload(directory, file);
	}

	/**
	 * 스트림을 업로드한다 — 서버에서 만든 파일(엑셀 등)을 올릴 때 쓴다.
	 *
	 * @throws CoreException INVALID_FILE / STORAGE_ERROR
	 */
	public static FileDto.StoredFile upload(String directory, String originalName, InputStream content, long size,
											String contentType) {
		return storage().upload(directory, originalName, content, size, contentType);
	}

	/**
	 * 파일 내용 스트림 — 호출측이 닫아야 한다.
	 *
	 * @throws CoreException INVALID_FILE(키 경로 조작) / NOT_FOUND / STORAGE_ERROR
	 */
	public static InputStream download(String key) {
		return storage().download(key);
	}

	/** 파일을 삭제한다 — 없으면 무시한다 */
	public static void delete(String key) {
		storage().delete(key);
	}

	/** 접근 URL — 로컬은 urlPrefix + key, S3 는 만료되는 presigned URL */
	public static String getUrl(String key) {
		return storage().getUrl(key);
	}

	/** 초기화 여부를 확인한다 — core.file.storage=s3 인데 AWS SDK 가 없으면 저장소가 만들어지지 않아 여기서 드러난다 */
	private static FileStorage storage() {
		final FileStorage current = fileStorage;
		if (current == null) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER,
				"FileUtils 미초기화 - core.file.storage 설정 확인 (s3 는 software.amazon.awssdk:s3 의존성 필요)");
		}
		return current;
	}
}
