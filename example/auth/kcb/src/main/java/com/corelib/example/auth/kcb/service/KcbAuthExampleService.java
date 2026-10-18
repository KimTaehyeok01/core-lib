// KcbAuthUtils 본인확인 결과로 회원 가입·로그인을 처리하는 예제 서비스 (회원은 메모리 보관)
package com.corelib.example.auth.kcb.service;

import com.corelib.auth.kcb.KcbAuthDto;
import com.corelib.auth.kcb.KcbAuthUtils;
import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.crypto.HashUtils;
import com.corelib.example.auth.kcb.dto.KcbAuthExampleDto;
import com.corelib.lang.MaskingUtils;
import com.corelib.lang.StringUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * KCB 휴대폰 본인확인 회원 서비스.
 * <ol>
 *   <li>{@link KcbAuthUtils#start} — 모듈 토큰 발급, 팝업 form 값 생성</li>
 *   <li>{@link KcbAuthUtils#confirm} — return_url 의 mdl_tkn 으로 결과 조회 (토큰당 1회)</li>
 *   <li>CI 해시로 회원 조회 → 있으면 로그인(휴대폰번호 갱신), 없으면 가입. 만 14세 미만은 가입 차단</li>
 * </ol>
 */
@Slf4j
@Service
public class KcbAuthExampleService {

	/** 개인정보보호법 — 만 14세 미만은 법정대리인 동의 없이 가입할 수 없다 */
	private static final int MIN_JOIN_AGE = 14;
	private static final ErrorCode UNDER_AGE = ErrorCode.of("MEMBER_UNDER_14", "만 14세 미만은 가입할 수 없습니다.", 403);

	/** 실서비스는 회원 테이블 (ci_hash unique) — 예제라 메모리에 둔다 */
	private final Map<String, KcbAuthExampleDto.Member> membersByCiHash = new ConcurrentHashMap<>();
	private final AtomicLong sequence = new AtomicLong();

	/** 모듈 토큰 발급 — return_url·사이트명은 auth.kcb.* 설정값 */
	public KcbAuthExampleDto.StartResponse start() {
		KcbAuthDto.StartResponse start = KcbAuthUtils.start(null);
		return KcbAuthExampleDto.StartResponse.builder()
											  .popupUrl(start.getPopupUrl())
											  .formFields(start.formFields())
											  .build();
	}

	/** return_url 로 온 mdl_tkn 으로 결과를 받아 가입 또는 로그인 */
	public KcbAuthExampleDto.VerifiedMember confirm(String mdlTkn) {
		KcbAuthDto.ConfirmResponse person = KcbAuthUtils.confirm(KcbAuthDto.ConfirmRequest.builder().mdlTkn(mdlTkn).build());
		if (StringUtils.isBlank(person.getCi())) {
			// 필드명이 계약 가이드와 다르면 여기서 걸린다 — person.getRaw() 로 실제 키를 확인해 매핑을 고친다
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "본인확인 결과에 CI 가 없습니다.");
		}

		String ciHash = HashUtils.sha256Hex(person.getCi());
		LocalDate birthDt = parseBirthDt(person.getBirthday());
		LocalDateTime now = LocalDateTime.now();

		KcbAuthExampleDto.Member existing = membersByCiHash.get(ciHash);
		if (existing != null) {
			KcbAuthExampleDto.Member updated = existing.toBuilder().telNo(person.getTelNo()).lastVerifiedAt(now).build();
			membersByCiHash.put(ciHash, updated);
			log.info("KCB 본인확인 로그인. memberId={}", updated.getMemberId());
			return toVerified(updated, false);
		}

		int age = Period.between(birthDt, LocalDate.now()).getYears();
		if (age < MIN_JOIN_AGE) {
			throw new CoreException(UNDER_AGE).addErrorLogData("age", age);
		}
		KcbAuthExampleDto.Member joined = KcbAuthExampleDto.Member.builder()
																  .memberId(sequence.incrementAndGet())
																  .ciHash(ciHash)
																  .name(person.getName())
																  .birthDt(birthDt)
																  .sexCd(person.getSexCd())
																  .nationalCd(person.getNationalCd())
																  .telNo(person.getTelNo())
																  .joinedAt(now)
																  .lastVerifiedAt(now)
																  .build();
		KcbAuthExampleDto.Member saved = membersByCiHash.putIfAbsent(ciHash, joined);
		if (saved != null) {
			return toVerified(saved, false);
		}
		log.info("KCB 본인확인 가입. memberId={}", joined.getMemberId());
		return toVerified(joined, true);
	}

	/** 가입 회원 목록 — 개인정보는 마스킹해서만 내보낸다 */
	public List<KcbAuthExampleDto.MemberSummary> members() {
		return membersByCiHash.values().stream()
							  .sorted(Comparator.comparing(KcbAuthExampleDto.Member::getMemberId))
							  .map(member -> KcbAuthExampleDto.MemberSummary.builder()
																			.memberId(member.getMemberId())
																			.maskedName(MaskingUtils.name(member.getName()))
																			.maskedTelNo(MaskingUtils.mobile(member.getTelNo()))
																			.joinedAt(member.getJoinedAt())
																			.lastVerifiedAt(member.getLastVerifiedAt())
																			.build())
							  .toList();
	}

	private static KcbAuthExampleDto.VerifiedMember toVerified(KcbAuthExampleDto.Member member, boolean isNewMember) {
		return KcbAuthExampleDto.VerifiedMember.builder()
											   .memberId(member.getMemberId())
											   .isNewMember(isNewMember)
											   .maskedName(MaskingUtils.name(member.getName()))
											   .birthYear(member.getBirthDt().getYear())
											   .age(Period.between(member.getBirthDt(), LocalDate.now()).getYears())
											   .sexCd(member.getSexCd())
											   .nationalCd(member.getNationalCd())
											   .maskedTelNo(MaskingUtils.mobile(member.getTelNo()))
											   .joinedAt(member.getJoinedAt())
											   .build();
	}

	/** KCB 생년월일은 yyyyMMdd */
	private static LocalDate parseBirthDt(String birthday) {
		try {
			return LocalDate.parse(StringUtils.defaultIfBlank(birthday, ""), DateTimeFormatter.BASIC_ISO_DATE);
		} catch (DateTimeParseException e) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "본인확인 결과의 생년월일 형식이 올바르지 않습니다.");
		}
	}
}
