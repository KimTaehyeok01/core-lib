package com.corelib.example.swagger.service;

import com.corelib.common.util.ValidateUtils;
import com.corelib.example.swagger.dto.ProductExampleDto;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/** 상품 예제 서비스 — 문서화 대상 API 의 실제 동작 */
@Service
public class ProductExampleService {

	/** 실서비스는 DB 테이블 — 예제라 메모리에 둔다 */
	private final Map<Long, ProductExampleDto.ProductResponse> products = new ConcurrentHashMap<>();
	private final AtomicLong sequence = new AtomicLong();

	public ProductExampleDto.ProductResponse create(ProductExampleDto.CreateRequest request) {
		ValidateUtils.validate(request);
		long id = sequence.incrementAndGet();
		ProductExampleDto.ProductResponse product = ProductExampleDto.ProductResponse.builder()
			.id(id).name(request.getName().trim()).price(request.getPrice()).build();
		products.put(id, product);
		return product;
	}

	public List<ProductExampleDto.ProductResponse> list() {
		return products.values().stream().sorted(Comparator.comparing(ProductExampleDto.ProductResponse::getId)).toList();
	}
}
