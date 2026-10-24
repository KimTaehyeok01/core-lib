package com.corelib.example.mail.service;

import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.crypto.HashUtils;
import com.corelib.crypto.HmacUtils;
import com.corelib.example.mail.dto.EmailVerificationExampleDto;
import com.corelib.lang.MaskingUtils;
import com.corelib.lang.RandomStringUtils;
import com.corelib.mail.MailUtils;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 이메일 인증 서비스 — 회원가입·이메일 변경 때 주소 소유를 확인한다.
 * <ul>
 *   <li>{@link RandomStringUtils#randomNumeric} 6자리 코드 → {@link MailUtils#sendHtml} 발송</li>
 *   <li>코드는 해시({@link HashUtils})만 보관, 비교는 상수 시간({@link HmacUtils#isEqual})</li>
 *   <li>재발송 대기·만료·오입력 5회 폐기 — 무차별 대입과 메일 폭탄 방지</li>
 * </ul>
 */
@Slf4j
@Service
public class EmailVerificationExampleService {

	private static final int CODE_LENGTH = 6;
	private static final int MAX_FAIL_COUNT = 5;
	private static final String SUBJECT = "[core-lib] 이메일 인증코드";

	private static final ErrorCode RESEND_TOO_SOON = ErrorCode.of("VERIFY_RESEND_TOO_SOON", "잠시 후 다시 요청해 주세요.", 429);
	private static final ErrorCode NOT_REQUESTED = ErrorCode.of("VERIFY_NOT_REQUESTED", "인증코드를 먼저 요청해 주세요.", 400);
	private static final ErrorCode EXPIRED = ErrorCode.of("VERIFY_EXPIRED", "인증코드가 만료됐습니다. 다시 요청해 주세요.", 400);
	private static final ErrorCode MISMATCH = ErrorCode.of("VERIFY_MISMATCH", "인증코드가 일치하지 않습니다.", 400);
	private static final ErrorCode TOO_MANY_FAILS = ErrorCode.of("VERIFY_TOO_MANY_FAILS", "인증코드를 여러 번 틀렸습니다. 다시 요청해 주세요.", 400);

	/**
	 * 실서비스는 Redis(TTL) 나 인증 테이블 — 예제라 메모리에 둔다. 키는 소문자 이메일.
	 * 쿨다운 검사·실패 카운트는 compute 한 번으로 원자 처리 — 실서비스는 DB 조건부 UPDATE(WHERE fail_count = ?) 로 같은 원자성 확보
	 */
	private final Map<String, EmailVerificationExampleDto.Pending> pendings = new ConcurrentHashMap<>();
	private final Duration codeTtl;
	private final Duration resendCooldown;

	public EmailVerificationExampleService(@Value("${example.verification.code-ttl}") Duration codeTtl,
										   @Value("${example.verification.resend-cooldown}") Duration resendCooldown) {
		this.codeTtl = codeTtl;
		this.resendCooldown = resendCooldown;
	}

	public EmailVerificationExampleDto.SendResponse send(EmailVerificationExampleDto.SendRequest request) {
		ValidateUtils.validate(request);
		String email = request.getEmail().trim();
		String key = email.toLowerCase(Locale.ROOT);
		LocalDateTime now = LocalDateTime.now();

		String code = RandomStringUtils.randomNumeric(CODE_LENGTH);
		EmailVerificationExampleDto.Pending pending = EmailVerificationExampleDto.Pending.builder()
																						 .codeHash(HashUtils.sha256Hex(code))
																						 .sentAt(now)
																						 .expiresAt(now.plus(codeTtl))
																						 .failCount(0)
																						 .build();
		// 쿨다운 검사와 기록을 한 번에 — 동시에 여러 번 눌러도 1통만 나간다 (함수가 예외를 던지면 기존 값 유지)
		pendings.compute(key, (k, previous) -> {
			if (previous != null && now.isBefore(previous.getSentAt().plus(resendCooldown))) {
				throw new CoreException(RESEND_TOO_SOON);
			}
			return pending;
		});
		removeExpired(now);
		try {
			MailUtils.sendHtml(email, SUBJECT, body(code));
		} catch (CoreException e) {
			pendings.remove(key, pending);    // 못 보낸 코드는 남기지 않는다 — 바로 다시 요청할 수 있게
			throw e;
		}
		log.info("이메일 인증코드 발송. to={}", MaskingUtils.email(email));
		return EmailVerificationExampleDto.SendResponse.builder()
													   .maskedEmail(MaskingUtils.email(email))
													   .expiresAt(pending.getExpiresAt())
													   .resendAvailableAt(now.plus(resendCooldown))
													   .build();
	}

	/**
	 * 맞으면 코드를 폐기한다(1회용). 틀리면 횟수를 올리고 5번째에 폐기.
	 * 판정·횟수 증가·폐기를 compute 한 번에 처리한다 — 동시에 오답을 보내도 전부 집계되고, 맞는 코드를 동시에 보내도 1건만 성공
	 */
	public EmailVerificationExampleDto.ConfirmResponse confirm(EmailVerificationExampleDto.ConfirmRequest request) {
		ValidateUtils.validate(request);
		String key = request.getEmail().trim().toLowerCase(Locale.ROOT);
		String codeHash = HashUtils.sha256Hex(request.getCode());
		LocalDateTime now = LocalDateTime.now();

		AtomicReference<CoreException> rejected = new AtomicReference<>();
		pendings.compute(key, (k, pending) -> {
			if (pending == null) {
				rejected.set(new CoreException(NOT_REQUESTED));
				return null;
			}
			if (now.isAfter(pending.getExpiresAt())) {
				rejected.set(new CoreException(EXPIRED));
				return null;
			}
			if (HmacUtils.isEqual(pending.getCodeHash(), codeHash)) {
				return null;
			}
			int failCount = pending.getFailCount() + 1;
			if (failCount >= MAX_FAIL_COUNT) {
				rejected.set(new CoreException(TOO_MANY_FAILS));
				return null;
			}
			rejected.set(new CoreException(MISMATCH, MISMATCH.message() + " (" + failCount + "/" + MAX_FAIL_COUNT + ")"));
			return pending.toBuilder().failCount(failCount).build();
		});
		removeExpired(now);
		if (rejected.get() != null) {
			throw rejected.get();
		}
		return EmailVerificationExampleDto.ConfirmResponse.builder()
														  .maskedEmail(MaskingUtils.email(request.getEmail()))
														  .verifiedAt(now)
														  .build();
	}

	/** 아무도 다시 요청·확인하지 않은 만료 코드가 쌓이지 않게 정리 — 실서비스는 Redis TTL 이 대신한다 (값이 바뀐 항목은 지우지 않는다) */
	private void removeExpired(LocalDateTime now) {
		pendings.values().removeIf(pending -> now.isAfter(pending.getExpiresAt()));
	}

	private String body(String code) {
		return """
			<div style="font-family:sans-serif">
			  <p>아래 인증코드를 입력해 주세요.</p>
			  <p style="font-size:28px;font-weight:bold;letter-spacing:6px">%s</p>
			  <p style="color:#666">%d분 안에 입력해야 합니다. 요청하지 않았다면 이 메일을 무시하세요.</p>
			</div>
			""".formatted(code, Math.max(codeTtl.toMinutes(), 1));
	}
}
