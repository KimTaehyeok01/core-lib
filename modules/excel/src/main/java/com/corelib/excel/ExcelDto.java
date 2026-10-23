package com.corelib.excel;

import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 엑셀·CSV 읽기 결과 */
public class ExcelDto {

	// === 읽기 결과 — 오류가 있어도 맞게 읽힌 행은 담는다. 전부 받을지 하나라도 틀리면 거절할지는 호출측이 정한다 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class ReadResult<T> {

		/** 오류 없이 읽힌 행 */
		private final List<T> rows;

		/** rows 와 같은 순서의 엑셀 행 번호 — 업무 검증(중복 등) 오류를 행 번호로 알릴 때 */
		private final List<Integer> rowNumbers;

		/** 행·열별 오류 — 값 원문은 담지 않는다 (개인정보일 수 있다) */
		private final List<RowError> errors;

		/** 데이터 행 수 (헤더 제외, 빈 행 제외) */
		private final int totalRows;

		public boolean isSuccess() {
			return errors.isEmpty();
		}
	}

	// === 오류 한 건 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class RowError {

		/** 엑셀에서 보이는 행 번호 (헤더가 1행) */
		private final int rowNumber;

		/** 헤더 이름 */
		private final String column;

		private final String message;
	}
}
