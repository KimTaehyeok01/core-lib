// file 예제 API (업로드·목록·다운로드·삭제)
package com.corelib.example.file.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.file.dto.FileExampleDto;
import com.corelib.example.file.service.FileExampleService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 첨부파일 API — 화면은 static/index.html */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileExampleController {

	private final FileExampleService fileExampleService;

	@PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ApiResponse<FileExampleDto.FileInfo> upload(@RequestPart("file") MultipartFile file) {
		return ApiResponse.success(fileExampleService.upload(file));
	}

	@GetMapping("/list")
	public ApiResponse<List<FileExampleDto.FileInfo>> list() {
		return ApiResponse.success(fileExampleService.list());
	}

	/**
	 * 다운로드 — 한글 파일명은 RFC 5987(filename*) 로 인코딩.
	 * 브라우저가 실행하지 않도록 항상 attachment + octet-stream 으로 내려준다 (업로드 시 신고한 MIME 은 믿지 않는다).
	 */
	@GetMapping("/download")
	public ResponseEntity<Resource> download(@RequestParam String key) {
		FileExampleDto.Download download = fileExampleService.download(key);
		ContentDisposition disposition = ContentDisposition.attachment()
														   .filename(download.getInfo().getOriginalName(), StandardCharsets.UTF_8)
														   .build();
		return ResponseEntity.ok()
							 .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
							 .header("X-Content-Type-Options", "nosniff")
							 .contentType(MediaType.APPLICATION_OCTET_STREAM)
							 .contentLength(download.getInfo().getSize())
							 .body(download.getResource());
	}

	@PostMapping("/delete")
	public ApiResponse<Void> delete(@RequestBody FileExampleDto.DeleteRequest request) {
		fileExampleService.delete(request);
		return ApiResponse.success();
	}
}
