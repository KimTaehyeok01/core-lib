package com.corelib.example.excel.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.common.response.PageResponse;
import com.corelib.example.excel.dto.MemberExcelExampleDto;
import com.corelib.example.excel.service.MemberExcelExampleService;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 회원 명단 API — 목록·엑셀/CSV 다운로드·양식·일괄 등록 (화면은 static/index.html) */
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberExcelExampleController {

	private final MemberExcelExampleService memberExcelExampleService;

	@GetMapping("/list")
	public ApiResponse<PageResponse<MemberExcelExampleDto.MemberSummary>> list(@RequestParam(defaultValue = "0") int page,
																			   @RequestParam(defaultValue = "10") int size) {
		return ApiResponse.success(memberExcelExampleService.list(page, size));
	}

	/** format=xlsx(기본) · csv */
	@GetMapping("/export")
	public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "xlsx") String format) {
		return download(memberExcelExampleService.export(format));
	}

	@GetMapping("/template")
	public ResponseEntity<byte[]> template() {
		return download(memberExcelExampleService.template());
	}

	@PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ApiResponse<MemberExcelExampleDto.ImportResult> importFile(@RequestPart("file") MultipartFile file) {
		return ApiResponse.success(memberExcelExampleService.importFile(file));
	}

	/** 한글 파일명은 filename*(UTF-8), 브라우저가 열지 않고 저장하게 attachment + nosniff */
	private static ResponseEntity<byte[]> download(MemberExcelExampleDto.FileResult file) {
		return ResponseEntity.ok()
							 .header(HttpHeaders.CONTENT_DISPOSITION,
								 ContentDisposition.attachment().filename(file.getFileName(), StandardCharsets.UTF_8).build().toString())
							 .header("X-Content-Type-Options", "nosniff")
							 .contentType(MediaType.parseMediaType(file.getContentType()))
							 .body(file.getBytes());
	}
}
