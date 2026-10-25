package com.corelib.common.response;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 목록 응답 — 화면이 페이지 번호·전체 건수로 페이지네이션을 그린다.
 * page 는 0 부터 (Spring Data 와 같다). Spring Data Page 변환은 {@link PageUtils#from}.
 */
public final class PageResponse<T> {

	private final List<T> content;
	private final int page;
	private final int size;
	private final long totalElements;
	private final int totalPages;
	private final boolean isFirst;
	private final boolean isLast;

	private PageResponse(List<T> content, int page, int size, long totalElements) {
		// List.copyOf 는 null 원소에서 NPE — 매핑 결과·메모리 목록의 null 항목도 그대로 담는다
		this.content = Collections.unmodifiableList(new ArrayList<>(content));
		this.page = page;
		this.size = size;
		this.totalElements = totalElements;
		this.totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
		this.isFirst = page == 0;
		this.isLast = totalPages == 0 || page >= totalPages - 1;
	}

	/** @param page 0 부터 */
	public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
		return new PageResponse<>(content, page, size, totalElements);
	}

	/**
	 * 메모리 목록을 잘라 한 페이지 — 건수가 적은 목록·예제용 (DB 는 쿼리로 자른다)
	 * @throws CoreException C400 (음수 page, 1 미만 size) — {@link PageUtils#pageable} 과 같은 기준
	 */
	public static <T> PageResponse<T> slice(List<T> all, int page, int size) {
		if (page < 0) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "page 는 0 이상이어야 합니다.");
		}
		if (size < 1) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "size 는 1 이상이어야 합니다.");
		}
		int from = (int) Math.min((long) page * size, all.size());
		int to = (int) Math.min((long) from + size, all.size());
		return new PageResponse<>(all.subList(from, to), page, size, all.size());
	}

	/** 항목만 바꾼다 — Entity 페이지를 응답 DTO 페이지로 */
	public <R> PageResponse<R> map(Function<? super T, ? extends R> mapper) {
		return new PageResponse<>(content.stream().<R>map(mapper).toList(), page, size, totalElements);
	}

	public List<T> getContent() {
		return content;
	}

	public int getPage() {
		return page;
	}

	public int getSize() {
		return size;
	}

	public long getTotalElements() {
		return totalElements;
	}

	public int getTotalPages() {
		return totalPages;
	}

	public boolean isFirst() {
		return isFirst;
	}

	public boolean isLast() {
		return isLast;
	}
}
