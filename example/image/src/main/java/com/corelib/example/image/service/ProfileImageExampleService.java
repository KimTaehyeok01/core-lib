package com.corelib.example.image.service;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.example.image.dto.ProfileImageExampleDto;
import com.corelib.image.ImageDto;
import com.corelib.image.ImageUtils;
import com.corelib.lang.DateTimeUtils;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 프로필 사진 서비스.
 * <ul>
 *   <li>업로드한 파일의 Content-Type·확장자는 믿지 않는다 — {@link ImageUtils#info} 로 실제 이미지인지 헤더를 읽어 확인</li>
 *   <li>원본을 한 번만 디코딩해 썸네일(100 정사각)·중간(400)·큰(1080) 3종 — 휴대폰 사진 회전 보정, EXIF(GPS) 제거</li>
 *   <li>원본 파일은 저장하지 않는다 — 위치정보가 든 원본을 남기지 않기 위해</li>
 * </ul>
 */
@Slf4j
@Service
public class ProfileImageExampleService {

	private static final List<ImageDto.Spec> SPECS = List.of(
		ImageDto.Spec.builder().name("thumbnail").width(100).height(100).mode(ImageDto.Mode.COVER).quality(0.8f).build(),
		ImageDto.Spec.builder().name("medium").width(400).height(400).build(),
		ImageDto.Spec.builder().name("large").width(1080).height(1080).build());

	/** 실서비스는 FileUtils(S3) + 이미지 테이블 — 예제라 메모리 */
	private final Map<String, ProfileImageExampleDto.StoredImage> images = new ConcurrentHashMap<>();

	public ProfileImageExampleDto.UploadResponse upload(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "사진 파일이 없습니다.");
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException e) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "파일을 읽지 못했습니다.");
		}
		ImageDto.Info original = ImageUtils.info(bytes);
		Map<String, ImageDto.Result> variants = new LinkedHashMap<>();
		ImageUtils.variants(bytes, SPECS).forEach(result -> variants.put(result.getName(), result));

		String imageId = UUID.randomUUID().toString();
		images.put(imageId, ProfileImageExampleDto.StoredImage.builder().imageId(imageId).original(original).variants(variants)
															 .uploadedAt(DateTimeUtils.now()).build());
		log.info("프로필 사진 업로드. imageId={}, 원본 {}x{} {}", imageId, original.getWidth(), original.getHeight(), original.getFormat());
		return ProfileImageExampleDto.UploadResponse.builder()
													.imageId(imageId)
													.originalWidth(original.getWidth())
													.originalHeight(original.getHeight())
													.originalFormat(original.getFormat())
													.originalBytes(bytes.length)
													.variants(variants.values().stream().map(result -> ProfileImageExampleDto.Variant.builder()
														.name(result.getName()).width(result.getWidth()).height(result.getHeight())
														.bytes(result.getBytes().length)
														.url("/api/profile-images/view?imageId=" + imageId + "&size=" + result.getName())
														.build()).toList())
													.build();
	}

	public ProfileImageExampleDto.ImageFile view(String imageId, String size) {
		ProfileImageExampleDto.StoredImage image = imageId == null ? null : images.get(imageId);
		ImageDto.Result result = image == null ? null : image.getVariants().get(size);
		if (result == null) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "사진을 찾을 수 없습니다.");
		}
		return ProfileImageExampleDto.ImageFile.builder().bytes(result.getBytes()).contentType(result.getFormat().getContentType()).build();
	}
}
