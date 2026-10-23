package com.corelib.example.excel.dto;

import com.corelib.excel.ExcelColumn;
import com.corelib.excel.ExcelDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 회원 명단 엑셀 예제 데이터 전송 객체 */
public class MemberExcelExampleDto {

	/** 회원 등급 */
	public enum Grade {
		BASIC,
		VIP
	}

	// === 엑셀·CSV 한 행 — 다운로드와 일괄 등록이 같은 정의를 쓴다 ===
	@Getter
	@Builder
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class MemberRow {

		@ExcelColumn(header = "이름", order = 1, required = true, width = 12)
		private String name;

		@ExcelColumn(header = "이메일", order = 2, required = true, width = 28)
		private String email;

		@ExcelColumn(header = "휴대폰", order = 3, width = 16)
		private String mobileNo;

		@ExcelColumn(header = "등급", order = 4, width = 8)
		private Grade grade;

		@ExcelColumn(header = "포인트", order = 5, format = "#,##0")
		private Long point;

		@ExcelColumn(header = "가입일", order = 6, width = 12)
		private LocalDate joinedDt;

		@ExcelColumn(header = "수신동의", order = 7, width = 10)
		private Boolean isAgreed;
	}

	// === 목록 항목 (화면용, 마스킹) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class MemberSummary {

		private final Long memberNo;

		private final String maskedName;

		private final String maskedEmail;

		private final Grade grade;

		private final Long point;

		private final String joinedDt;
	}

	// === 일괄 등록 결과 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class ImportResult {

		/** 하나라도 틀리면 0 — 전부 등록하거나 하나도 안 한다 */
		private final int importedCount;

		private final int totalRows;

		/** 등록된 회원 포인트 평균 (원 단위 반올림) */
		private final BigDecimal averagePoint;

		/** 행·열별 오류 — 엑셀을 고쳐 다시 올린다 */
		private final List<ExcelDto.RowError> errors;
	}

	// === 다운로드 파일 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class FileResult {

		private final String fileName;

		private final String contentType;

		private final byte[] bytes;
	}
}
