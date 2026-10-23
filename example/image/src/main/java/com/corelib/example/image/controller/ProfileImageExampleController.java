package com.corelib.example.image.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.image.dto.ProfileImageExampleDto;
import com.corelib.example.image.service.ProfileImageExampleService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 프로필 사진 API — 업로드·규격별 보기 (화면은 static/index.html) */
@RestController
@RequestMapping("/api/profile-images")
@RequiredArgsConstructor
public class ProfileImageExampleController {

	private final ProfileImageExampleService profileImageExampleService;

	@PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ApiResponse<ProfileImageExampleDto.UploadResponse> upload(@RequestPart("file") MultipartFile file) {
		return ApiResponse.success(profileImageExampleService.upload(file));
	}

	/** size=thumbnail · medium · large — 한 번 만든 결과는 바뀌지 않아 오래 캐시 */
	@GetMapping("/view")
	public ResponseEntity<byte[]> view(@RequestParam String imageId, @RequestParam(defaultValue = "medium") String size) {
		ProfileImageExampleDto.ImageFile image = profileImageExampleService.view(imageId, size);
		return ResponseEntity.ok()
							 .contentType(MediaType.parseMediaType(image.getContentType()))
							 .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
							 .header("X-Content-Type-Options", "nosniff")
							 .body(image.getBytes());
	}
}
