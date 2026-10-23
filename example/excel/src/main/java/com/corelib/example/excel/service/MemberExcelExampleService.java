package com.corelib.example.excel.service;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.response.PageResponse;
import com.corelib.example.excel.dto.MemberExcelExampleDto;
import com.corelib.excel.CsvUtils;
import com.corelib.excel.ExcelDto;
import com.corelib.excel.ExcelUtils;
import com.corelib.lang.DateTimeUtils;
import com.corelib.lang.MaskingUtils;
import com.corelib.lang.RoundUtils;
import com.corelib.lang.StringUtils;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 회원 명단 엑셀 서비스.
 * <ul>
 *   <li>다운로드 — 전체 명단 xlsx·csv, 빈 양식 (실서비스는 관리자만, 다운로드 이력을 남긴다 — 개인정보)</li>
 *   <li>일괄 등록 — 형식 오류(excel 모듈) + 업무 오류(이메일 형식·중복)를 모아, 하나라도 있으면 아무것도 등록하지 않는다</li>
 *   <li>목록 — PageResponse 로 페이지, 이름·이메일 마스킹</li>
 * </ul>
 */
@Service
public class MemberExcelExampleService {

	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

	/** 실서비스는 회원 테이블 — 예제라 메모리 (키: 회원번호) */
	private final Map<Long, MemberExcelExampleDto.MemberRow> members = new TreeMap<>();
	private final AtomicLong sequence = new AtomicLong();

	public MemberExcelExampleService() {
		save(List.of(
			row("홍길동", "hong@example.com", "01012345678", MemberExcelExampleDto.Grade.VIP, 15000L, "2026-01-02", true),
			row("김철수", "kim@example.com", "01022223333", MemberExcelExampleDto.Grade.BASIC, 3000L, "2026-03-15", false),
			row("이영희", "lee@example.com", null, MemberExcelExampleDto.Grade.BASIC, 0L, "2026-10-01", true)));
	}

	public synchronized PageResponse<MemberExcelExampleDto.MemberSummary> list(int page, int size) {
		if (page < 0 || size < 1 || size > 100) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "page 는 0 이상, size 는 1~100 입니다.");
		}
		List<Map.Entry<Long, MemberExcelExampleDto.MemberRow>> entries = new ArrayList<>(members.entrySet());
		return PageResponse.slice(entries, page, size).map(entry -> MemberExcelExampleDto.MemberSummary.builder()
			.memberNo(entry.getKey())
			.maskedName(MaskingUtils.name(entry.getValue().getName()))
			.maskedEmail(MaskingUtils.email(entry.getValue().getEmail()))
			.grade(entry.getValue().getGrade())
			.point(entry.getValue().getPoint())
			.joinedDt(DateTimeUtils.format(entry.getValue().getJoinedDt()))
			.build());
	}

	public synchronized MemberExcelExampleDto.FileResult export(String format) {
		List<MemberExcelExampleDto.MemberRow> rows = List.copyOf(members.values());
		String date = DateTimeUtils.format(DateTimeUtils.today(), DateTimeUtils.DATE_COMPACT);
		if ("csv".equalsIgnoreCase(format)) {
			return file("회원명단_" + date + ".csv", CsvUtils.CONTENT_TYPE, CsvUtils.write(rows, MemberExcelExampleDto.MemberRow.class));
		}
		return file("회원명단_" + date + ".xlsx", ExcelUtils.CONTENT_TYPE, ExcelUtils.write(rows, MemberExcelExampleDto.MemberRow.class, "회원"));
	}

	public MemberExcelExampleDto.FileResult template() {
		return file("회원등록_양식.xlsx", ExcelUtils.CONTENT_TYPE, ExcelUtils.template(MemberExcelExampleDto.MemberRow.class, "회원등록"));
	}

	/** 일괄 등록 — 확장자로 xlsx·csv 구분, 전부 맞아야 등록 */
	public synchronized MemberExcelExampleDto.ImportResult importFile(MultipartFile file) {
		String name = StringUtils.defaultIfBlank(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
		ExcelDto.ReadResult<MemberExcelExampleDto.MemberRow> read;
		try (InputStream content = file.getInputStream()) {
			if (name.endsWith(".xlsx")) {
				read = ExcelUtils.read(content, MemberExcelExampleDto.MemberRow.class);
			} else if (name.endsWith(".csv")) {
				read = CsvUtils.read(content, MemberExcelExampleDto.MemberRow.class);
			} else {
				throw new CoreException(CommonErrorCode.BAD_REQUEST, "xlsx 또는 csv 파일만 올릴 수 있습니다.");
			}
		} catch (IOException e) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "파일을 읽지 못했습니다.");
		}

		List<ExcelDto.RowError> errors = new ArrayList<>(read.getErrors());
		Set<String> existing = new HashSet<>();
		members.values().forEach(member -> existing.add(member.getEmail().toLowerCase(Locale.ROOT)));
		Set<String> inFile = new HashSet<>();
		// 형식 오류 없이 읽힌 행만 업무 규칙 검사 — 오류는 그 행의 엑셀 행 번호로 알린다
		for (int i = 0; i < read.getRows().size(); i++) {
			String email = read.getRows().get(i).getEmail().trim().toLowerCase(Locale.ROOT);
			int rowNumber = read.getRowNumbers().get(i);
			if (!EMAIL.matcher(email).matches()) {
				errors.add(businessError(rowNumber, "이메일 형식이 아닙니다."));
			} else if (existing.contains(email)) {
				errors.add(businessError(rowNumber, "이미 가입된 이메일입니다."));
			} else if (!inFile.add(email)) {
				errors.add(businessError(rowNumber, "파일 안에서 중복된 이메일입니다."));
			}
		}
		if (!errors.isEmpty()) {
			return MemberExcelExampleDto.ImportResult.builder().importedCount(0).totalRows(read.getTotalRows()).errors(errors).build();
		}
		save(read.getRows());
		long totalPoint = read.getRows().stream().mapToLong(row -> row.getPoint() == null ? 0 : row.getPoint()).sum();
		BigDecimal average = read.getRows().isEmpty() ? BigDecimal.ZERO
			: RoundUtils.round(BigDecimal.valueOf(totalPoint).divide(BigDecimal.valueOf(read.getRows().size()), 2, java.math.RoundingMode.HALF_UP), 0);
		return MemberExcelExampleDto.ImportResult.builder().importedCount(read.getRows().size()).totalRows(read.getTotalRows())
												 .averagePoint(average).errors(List.of()).build();
	}

	private void save(List<MemberExcelExampleDto.MemberRow> rows) {
		rows.forEach(row -> members.put(sequence.incrementAndGet(), row));
	}

	private static ExcelDto.RowError businessError(int rowNumber, String message) {
		return ExcelDto.RowError.builder().rowNumber(rowNumber).column("이메일").message(message).build();
	}

	private static MemberExcelExampleDto.FileResult file(String fileName, String contentType, byte[] bytes) {
		return MemberExcelExampleDto.FileResult.builder().fileName(fileName).contentType(contentType).bytes(bytes).build();
	}

	private static MemberExcelExampleDto.MemberRow row(String name, String email, String mobileNo, MemberExcelExampleDto.Grade grade,
													   Long point, String joinedDt, boolean isAgreed) {
		return MemberExcelExampleDto.MemberRow.builder().name(name).email(email).mobileNo(mobileNo).grade(grade).point(point)
											  .joinedDt(DateTimeUtils.parseDate(joinedDt)).isAgreed(isAgreed).build();
	}
}
