package com.corelib.example.webclient.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.webclient.dto.ShipmentExampleDto;
import com.corelib.example.webclient.service.ShipmentExampleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 택배 API — 접수(택배사 POST)·상세·배송 추적(택배사 GET) */
@RestController
@RequestMapping("/api/shipments")
@RequiredArgsConstructor
public class ShipmentExampleController {

	private final ShipmentExampleService shipmentExampleService;

	@PostMapping("/register")
	public ApiResponse<ShipmentExampleDto.ShipmentResponse> register(@RequestBody ShipmentExampleDto.RegisterRequest request) {
		return ApiResponse.success(shipmentExampleService.register(request));
	}

	@GetMapping("/detail")
	public ApiResponse<ShipmentExampleDto.ShipmentResponse> detail(@RequestParam String orderNo) {
		return ApiResponse.success(shipmentExampleService.detail(orderNo));
	}

	@GetMapping("/track")
	public ApiResponse<ShipmentExampleDto.TrackResponse> track(@RequestParam String orderNo) {
		return ApiResponse.success(shipmentExampleService.track(orderNo));
	}
}
