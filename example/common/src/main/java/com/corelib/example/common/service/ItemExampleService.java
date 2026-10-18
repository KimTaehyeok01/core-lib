// common 모듈의 예외·검증 사용 패턴을 보여주는 상품 예제 서비스 (메모리 보관)
package com.corelib.example.common.service;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.example.common.dto.ItemExampleDto;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/** 상품 예제 서비스 — 검증·예외 처리 패턴 */
@Service
public class ItemExampleService {

	/** 도메인 전용 에러코드 — enum 이 없을 때는 ErrorCode.of 로 즉석에서 만든다 (보통은 enum 으로 모아둔다) */
	private static final ErrorCode SOLD_OUT = ErrorCode.of("ITEM_SOLD_OUT", "품절된 상품입니다.", 409);

	/** 실서비스는 DB 테이블 — 예제라 메모리에 둔다 */
	private final Map<Long, ItemExampleDto.ItemResponse> items = new ConcurrentHashMap<>();
	private final AtomicLong sequence = new AtomicLong();

	/** 등록 — 요청 DTO 어노테이션 검증은 ValidateUtils 한 줄 (위반이면 C400) */
	public ItemExampleDto.ItemResponse create(ItemExampleDto.CreateRequest request) {
		ValidateUtils.validate(request);
		long id = sequence.incrementAndGet();
		ItemExampleDto.ItemResponse item = ItemExampleDto.ItemResponse.builder()
																	  .id(id)
																	  .name(request.getName().trim())
																	  .price(request.getPrice())
																	  .stock(request.getStock())
																	  .build();
		items.put(id, item);
		return item;
	}

	/** 조회 — 없으면 CoreException(NOT_FOUND). 로그에만 남길 값은 addErrorLogData (응답에는 안 나감) */
	public ItemExampleDto.ItemResponse detail(Long id) {
		ItemExampleDto.ItemResponse item = items.get(id);
		if (item == null) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다.").addErrorLogData("itemId", id);
		}
		return item;
	}

	public List<ItemExampleDto.ItemResponse> list() {
		return items.values().stream().sorted(Comparator.comparing(ItemExampleDto.ItemResponse::getId)).toList();
	}

	/** 구매 — 업무 규칙 위반은 도메인 에러코드로 (409 ITEM_SOLD_OUT) */
	public ItemExampleDto.ItemResponse purchase(ItemExampleDto.PurchaseRequest request) {
		ValidateUtils.validate(request);
		ItemExampleDto.ItemResponse current = detail(request.getItemId());
		ItemExampleDto.ItemResponse updated = items.computeIfPresent(current.getId(), (id, item) -> {
			if (item.getStock() <= 0) {
				throw new CoreException(SOLD_OUT).addErrorLogData("itemId", id);
			}
			return ItemExampleDto.ItemResponse.builder().id(id).name(item.getName()).price(item.getPrice()).stock(item.getStock() - 1).build();
		});
		return updated;
	}

	/** 예상 못 한 오류 — 전역 핸들러가 내부 메시지를 숨기고 500 C500 으로 통일한다 */
	public void unexpected() {
		throw new IllegalStateException("DB 접속 정보 같은 내부 메시지 - 응답으로 나가면 안 됨");
	}
}
