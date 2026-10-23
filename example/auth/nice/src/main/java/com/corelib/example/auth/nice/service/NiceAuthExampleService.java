package com.corelib.example.auth.nice.service;

import com.corelib.auth.nice.NiceAuthDto;
import com.corelib.auth.nice.NiceAuthUtils;
import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.crypto.HashUtils;
import com.corelib.example.auth.nice.dto.NiceAuthExampleDto;
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
 * 본인인증 회원 서비스.
 * <ol>
 *   <li>{@link NiceAuthUtils#verification} — 토큰·표준창 URL 발급</li>
 *   <li>{@link NiceAuthUtils#verificationConfirm} — 결과 조회·무결성 검증·복호화</li>
 *   <li>CI 해시로 회원 조회 → 있으면 로그인(휴대폰번호 갱신), 없으면 가입. 만 14세 미만은 가입 차단</li>
 * </ol>
 */
@Slf4j
@Service
public class NiceAuthExampleService {

	/** 개인정보보호법 — 만 14세 미만은 법정대리인 동의 없이 가입할 수 없다 */
	private static final int MIN_JOIN_AGE = 14;
	private static final ErrorCode UNDER_AGE = ErrorCode.of("MEMBER_UNDER_14", "만 14세 미만은 가입할 수 없습니다.", 403);

	/** 실서비스는 회원 테이블 (ci_hash unique) — 예제라 메모리에 둔다 */
	private final Map<String, NiceAuthExampleDto.Member> membersByCiHash = new ConcurrentHashMap<>();
	private final AtomicLong sequence = new AtomicLong();

	/** 토큰 발급 + 표준창 URL 발급 — return_url·인증수단은 auth.nice.* 설정값 사용 */
	public NiceAuthExampleDto.StartResult start() {
		NiceAuthDto.VerificationResponse response = NiceAuthUtils.verification(null);
		return NiceAuthExampleDto.StartResult.builder()
											 .authUrl(response.getAuthUrl())
											 .txNo(response.getTxNo())
											 .build();
	}

	/** return_url 로 온 web_transaction_id 로 인증 결과를 받아 가입 또는 로그인 */
	public NiceAuthExampleDto.VerifiedMember confirm(String txNo, String webTransactionId) {
		NiceAuthDto.VerificationConfirmResponse person = NiceAuthUtils.verificationConfirm(
			NiceAuthDto.VerificationConfirmRequest.builder().txNo(txNo).webTransactionId(webTransactionId).build());
		if (StringUtils.isBlank(person.getCi())) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "본인인증 결과에 CI 가 없습니다.");
		}

		String ciHash = HashUtils.sha256Hex(person.getCi());
		LocalDate birthDt = parseBirthDt(person.getBirthdate());
		LocalDateTime now = LocalDateTime.now();

		NiceAuthExampleDto.Member existing = membersByCiHash.get(ciHash);
		if (existing != null) {
			// 기존 회원 — 번호 이동 등으로 바뀐 휴대폰번호만 갱신
			NiceAuthExampleDto.Member updated = existing.toBuilder().mobileNo(person.getMobileNo()).lastVerifiedAt(now).build();
			membersByCiHash.put(ciHash, updated);
			log.info("본인인증 로그인. memberId={}", updated.getMemberId());
			return toVerified(updated, false);
		}

		int age = Period.between(birthDt, LocalDate.now()).getYears();
		if (age < MIN_JOIN_AGE) {
			throw new CoreException(UNDER_AGE).addErrorLogData("age", age);
		}
		NiceAuthExampleDto.Member joined = NiceAuthExampleDto.Member.builder()
																	.memberId(sequence.incrementAndGet())
																	.ciHash(ciHash)
																	.name(person.getName())
																	.birthDt(birthDt)
																	.gender(person.getGender())
																	.nationalInfo(person.getNationalInfo())
																	.mobileNo(person.getMobileNo())
																	.joinedAt(now)
																	.lastVerifiedAt(now)
																	.build();
		// 동시에 같은 사람이 두 번 인증해도 회원은 하나 — 먼저 들어간 쪽을 쓴다
		NiceAuthExampleDto.Member saved = membersByCiHash.putIfAbsent(ciHash, joined);
		if (saved != null) {
			return toVerified(saved, false);
		}
		log.info("본인인증 가입. memberId={}", joined.getMemberId());
		return toVerified(joined, true);
	}

	/** 가입 회원 목록 — 개인정보는 마스킹해서만 내보낸다 */
	public List<NiceAuthExampleDto.MemberSummary> members() {
		return membersByCiHash.values().stream()
							  .sorted(Comparator.comparing(NiceAuthExampleDto.Member::getMemberId))
							  .map(member -> NiceAuthExampleDto.MemberSummary.builder()
																			 .memberId(member.getMemberId())
																			 .maskedName(MaskingUtils.name(member.getName()))
																			 .maskedMobileNo(MaskingUtils.mobile(member.getMobileNo()))
																			 .joinedAt(member.getJoinedAt())
																			 .lastVerifiedAt(member.getLastVerifiedAt())
																			 .build())
							  .toList();
	}

	private static NiceAuthExampleDto.VerifiedMember toVerified(NiceAuthExampleDto.Member member, boolean isNewMember) {
		return NiceAuthExampleDto.VerifiedMember.builder()
												.memberId(member.getMemberId())
												.isNewMember(isNewMember)
												.maskedName(MaskingUtils.name(member.getName()))
												.birthYear(member.getBirthDt().getYear())
												.age(Period.between(member.getBirthDt(), LocalDate.now()).getYears())
												.gender(member.getGender())
												.nationalInfo(member.getNationalInfo())
												.maskedMobileNo(MaskingUtils.mobile(member.getMobileNo()))
												.joinedAt(member.getJoinedAt())
												.build();
	}

	/** NICE birthdate 는 yyyyMMdd */
	private static LocalDate parseBirthDt(String birthdate) {
		try {
			return LocalDate.parse(StringUtils.defaultIfBlank(birthdate, ""), DateTimeFormatter.BASIC_ISO_DATE);
		} catch (DateTimeParseException e) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "본인인증 결과의 생년월일 형식이 올바르지 않습니다.");
		}
	}
}
