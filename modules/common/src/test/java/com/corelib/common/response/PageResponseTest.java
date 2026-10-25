package com.corelib.common.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PageResponseTest {

	@Test
	@DisplayName("페이지 계산 — 전체 페이지 수, 처음·마지막, 빈 목록")
	void of() {
		PageResponse<String> page = PageResponse.of(List.of("a", "b"), 1, 2, 5);

		assertThat(page.getTotalPages()).isEqualTo(3);
		assertThat(page.isFirst()).isFalse();
		assertThat(page.isLast()).isFalse();
		assertThat(PageResponse.of(List.of("e"), 2, 2, 5).isLast()).isTrue();

		PageResponse<String> empty = PageResponse.of(List.of(), 0, 10, 0);
		assertThat(empty.getTotalPages()).isZero();
		assertThat(empty.isFirst()).isTrue();
		assertThat(empty.isLast()).isTrue();
	}

	@Test
	@DisplayName("slice — 메모리 목록 자르기, 범위를 넘으면 빈 페이지 / map — 항목만 바꿈")
	void sliceAndMap() {
		List<Integer> all = IntStream.rangeClosed(1, 25).boxed().toList();

		assertThat(PageResponse.slice(all, 2, 10).getContent()).containsExactly(21, 22, 23, 24, 25);
		assertThat(PageResponse.slice(all, 9, 10).getContent()).isEmpty();
		assertThat(PageResponse.slice(all, 0, 10).map(n -> "#" + n).getContent()).startsWith("#1", "#2");
	}

	@Test
	@DisplayName("slice - 음수 page·1 미만 size 는 C400 (IndexOutOfBounds 500 아님), null 원소는 그대로 담음")
	void sliceInvalidAndNull() {
		List<Integer> all = IntStream.rangeClosed(1, 5).boxed().toList();
		for (int[] pageSize : new int[][] {{-1, 10}, {0, 0}, {0, -3}}) {
			assertThatThrownBy(() -> PageResponse.slice(all, pageSize[0], pageSize[1]))
				.isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.BAD_REQUEST));
		}

		assertThat(PageResponse.slice(Arrays.asList("a", null, "c"), 0, 10).getContent()).containsExactly("a", null, "c");
		assertThat(PageResponse.slice(List.of("a", "b"), 0, 10).map(s -> "a".equals(s) ? null : s).getContent()).containsExactly(null, "b");
	}

	@Test
	@DisplayName("Spring Data — Page 변환, page·size 검증 (size 100 초과·음수 page 는 C400)")
	void springData() {
		PageResponse<String> page = PageUtils.from(new PageImpl<>(List.of("x"), PageRequest.of(3, 1), 10));
		assertThat(page.getPage()).isEqualTo(3);
		assertThat(page.getTotalElements()).isEqualTo(10);

		Pageable pageable = PageUtils.pageable(0, 20, Sort.by("id").descending());
		assertThat(pageable.getPageSize()).isEqualTo(20);
		assertThatThrownBy(() -> PageUtils.pageable(0, 1000, null)).isInstanceOf(CoreException.class);
		assertThatThrownBy(() -> PageUtils.pageable(-1, 10, null)).isInstanceOf(CoreException.class);
	}
}
