package com.corelib.common.response;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 페이지 요청 검증·Spring Data 변환 — spring-data-commons 가 있는 앱(JPA·Mongo 등)에서 쓴다.
 * PageResponse 와 분리한 이유: spring-data 가 없는 앱에서도 PageResponse 를 JSON 으로 쓸 수 있게.
 */
public final class PageUtils {

	/** 한 페이지 최대 건수 — 화면이 size=100000 을 보내 DB 를 긁지 못하게 */
	public static final int MAX_SIZE = 100;

	private PageUtils() {
	}

	/** Spring Data Page → PageResponse */
	public static <T> PageResponse<T> from(Page<T> page) {
		return PageResponse.of(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
	}

	/**
	 * 화면이 보낸 page·size 검증 후 Pageable — page 0 부터, size 1~100
	 * @throws CoreException C400 (음수 page, 범위 밖 size)
	 */
	public static Pageable pageable(int page, int size, Sort sort) {
		if (page < 0) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "page 는 0 이상이어야 합니다.");
		}
		if (size < 1 || size > MAX_SIZE) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "size 는 1~" + MAX_SIZE + " 사이여야 합니다.");
		}
		return PageRequest.of(page, size, sort == null ? Sort.unsorted() : sort);
	}
}
