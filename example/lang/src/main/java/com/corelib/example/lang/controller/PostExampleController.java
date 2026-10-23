package com.corelib.example.lang.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.lang.dto.PostExampleDto;
import com.corelib.example.lang.service.PostExampleService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 게시판 API — 등록·목록·상세·삭제 */
@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostExampleController {

	private final PostExampleService postExampleService;

	@PostMapping("/create")
	public ApiResponse<PostExampleDto.CreateResponse> create(@RequestBody PostExampleDto.CreateRequest request) {
		return ApiResponse.success(postExampleService.create(request));
	}

	@GetMapping("/list")
	public ApiResponse<List<PostExampleDto.PostSummary>> list(@RequestParam(required = false) String tag) {
		return ApiResponse.success(postExampleService.list(tag));
	}

	@GetMapping("/detail")
	public ApiResponse<PostExampleDto.PostDetail> detail(@RequestParam String shareCode) {
		return ApiResponse.success(postExampleService.detail(shareCode));
	}

	@PostMapping("/delete")
	public ApiResponse<Void> delete(@RequestBody PostExampleDto.DeleteRequest request) {
		postExampleService.delete(request);
		return ApiResponse.success(null);
	}
}
