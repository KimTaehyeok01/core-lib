package com.corelib.example.auth.otp.service;

import com.corelib.auth.otp.OtpDto;
import com.corelib.auth.otp.OtpUtils;
import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.crypto.AesUtils;
import com.corelib.crypto.Base64Utils;
import com.corelib.example.auth.otp.dto.OtpExampleDto;
import com.corelib.lang.DateTimeUtils;
import com.corelib.lang.StringUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 2단계 인증(OTP) 서비스.
 * <ol>
 *   <li>setup — {@link OtpUtils#register} 로 비밀키·QR URI. 비밀키는 AES-GCM 으로 암호화해 "등록 중" 상태로 저장</li>
 *   <li>activate — 회원이 OTP 앱 코드를 넣어 맞으면 사용 시작 (QR 을 제대로 찍었는지 확인)</li>
 *   <li>verify — 로그인 2단계. 같은 코드 재사용 차단(timeStep), 연속 실패하면 잠금</li>
 *   <li>disable — 현재 코드를 넣어야만 해제 (세션 탈취만으로 끄지 못하게)</li>
 * </ol>
 */
@Slf4j
@Service
public class OtpExampleService {

	private static final ErrorCode INVALID_CODE = ErrorCode.of("OTP_INVALID", "인증 코드가 올바르지 않습니다.", 401);
	private static final ErrorCode LOCKED = ErrorCode.of("OTP_LOCKED", "인증을 여러 번 실패했습니다. 잠시 후 다시 시도해 주세요.", 423);
	private static final ErrorCode NOT_ENABLED = ErrorCode.of("OTP_NOT_ENABLED", "2단계 인증이 켜져 있지 않습니다.", 400);
	private static final int GCM_IV_BYTES = AesUtils.Mode.GCM.getIvLength();

	/** 실서비스는 회원 테이블 — 예제라 메모리 */
	private final Map<String, OtpExampleDto.OtpState> states = new ConcurrentHashMap<>();
	private final byte[] secretKey;
	private final int maxFailures;
	private final Duration lockDuration;

	public OtpExampleService(@Value("${example.otp.secret-key:}") String secretKeyBase64,
							 @Value("${example.otp.max-failures:5}") int maxFailures,
							 @Value("${example.otp.lock-duration:5m}") Duration lockDuration) {
		if (StringUtils.isBlank(secretKeyBase64)) {
			log.warn("example.otp.secret-key 미설정 - 임시 키를 만듭니다. 재시작하면 등록한 OTP 를 복호화할 수 없습니다.");
			this.secretKey = AesUtils.generateKey(32);
		} else {
			this.secretKey = Base64Utils.decode(secretKeyBase64.trim());
		}
		this.maxFailures = maxFailures;
		this.lockDuration = lockDuration;
	}

	/** 등록 시작 — 이미 켜져 있으면 해제부터 하게 한다 (모르는 사이 비밀키가 바뀌지 않게) */
	public OtpExampleDto.SetupResponse setup(OtpExampleDto.SetupRequest request) {
		ValidateUtils.validate(request);
		OtpExampleDto.OtpState current = states.get(request.getMemberId());
		if (current != null && current.isEnabled()) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "이미 2단계 인증을 쓰고 있습니다. 해제한 뒤 다시 등록하세요.");
		}
		OtpDto.Registration registration = OtpUtils.register(request.getAccountName());
		states.put(request.getMemberId(), OtpExampleDto.OtpState.builder()
															  .memberId(request.getMemberId())
															  .encryptedSecret(encrypt(registration.getSecret()))
															  .isEnabled(false)
															  .lastUsedTimeStep(-1)
															  .build());
		return OtpExampleDto.SetupResponse.builder()
										  .manualKey(registration.getSecret().replaceAll("(.{4})(?!$)", "$1 "))
										  .otpauthUri(registration.getOtpauthUri())
										  .build();
	}

	/** 활성화 — 등록 중인 비밀키로 코드 확인 */
	public OtpExampleDto.StatusResponse activate(OtpExampleDto.CodeRequest request) {
		ValidateUtils.validate(request);
		OtpExampleDto.OtpState state = find(request.getMemberId());
		OtpExampleDto.OtpState verified = check(state, request.getCode());
		OtpExampleDto.OtpState enabled = verified.toBuilder().isEnabled(true).enabledAt(DateTimeUtils.now()).build();
		states.put(enabled.getMemberId(), enabled);
		return toStatus(enabled);
	}

	/** 로그인 2단계 — 1단계(비밀번호) 성공 뒤에 부른다 */
	public OtpExampleDto.StatusResponse verify(OtpExampleDto.CodeRequest request) {
		ValidateUtils.validate(request);
		OtpExampleDto.OtpState state = find(request.getMemberId());
		if (!state.isEnabled()) {
			throw new CoreException(NOT_ENABLED);
		}
		return toStatus(check(state, request.getCode()));
	}

	/** 해제 — 현재 코드가 맞아야 한다 */
	public OtpExampleDto.StatusResponse disable(OtpExampleDto.CodeRequest request) {
		ValidateUtils.validate(request);
		OtpExampleDto.OtpState state = find(request.getMemberId());
		if (!state.isEnabled()) {
			throw new CoreException(NOT_ENABLED);
		}
		check(state, request.getCode());
		states.remove(state.getMemberId());
		return OtpExampleDto.StatusResponse.builder().memberId(state.getMemberId()).isEnabled(false).build();
	}

	public OtpExampleDto.StatusResponse status(String memberId) {
		OtpExampleDto.OtpState state = states.get(memberId);
		return state == null ? OtpExampleDto.StatusResponse.builder().memberId(memberId).isEnabled(false).build() : toStatus(state);
	}

	/** 잠금 확인 → 코드 검증(재사용 차단) → 성공이면 실패 횟수 초기화, 실패면 증가·잠금 */
	private OtpExampleDto.OtpState check(OtpExampleDto.OtpState state, String code) {
		LocalDateTime now = DateTimeUtils.now();
		if (state.getLockedUntil() != null && now.isBefore(state.getLockedUntil())) {
			throw new CoreException(LOCKED);
		}
		OtpDto.VerifyResult result = OtpUtils.verify(decrypt(state.getEncryptedSecret()), code, state.getLastUsedTimeStep());
		if (!result.isValid()) {
			int failures = state.getFailureCount() + 1;
			boolean isLocked = failures >= maxFailures;
			states.put(state.getMemberId(), state.toBuilder()
												 .failureCount(isLocked ? 0 : failures)
												 .lockedUntil(isLocked ? now.plus(lockDuration) : null)
												 .build());
			if (isLocked) {
				log.warn("OTP 연속 실패로 잠금. memberId={}", state.getMemberId());
				throw new CoreException(LOCKED);
			}
			throw new CoreException(INVALID_CODE, INVALID_CODE.message() + " (" + failures + "/" + maxFailures + ")");
		}
		OtpExampleDto.OtpState passed = state.toBuilder().lastUsedTimeStep(result.getTimeStep()).failureCount(0).lockedUntil(null).build();
		states.put(passed.getMemberId(), passed);
		return passed;
	}

	private OtpExampleDto.OtpState find(String memberId) {
		OtpExampleDto.OtpState state = states.get(memberId);
		if (state == null) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "2단계 인증 등록 정보가 없습니다.");
		}
		return state;
	}

	private static OtpExampleDto.StatusResponse toStatus(OtpExampleDto.OtpState state) {
		return OtpExampleDto.StatusResponse.builder()
										   .memberId(state.getMemberId())
										   .isEnabled(state.isEnabled())
										   .enabledAt(DateTimeUtils.format(state.getEnabledAt()))
										   .lockedUntil(DateTimeUtils.format(state.getLockedUntil()))
										   .build();
	}

	private String encrypt(String secret) {
		return Base64Utils.encodeUrl(AesUtils.encryptWithIv(secret.getBytes(StandardCharsets.UTF_8), secretKey, AesUtils.Mode.GCM));
	}

	private String decrypt(String sealed) {
		return new String(AesUtils.decryptWithIv(Base64Utils.decodeUrl(sealed), secretKey, AesUtils.Mode.GCM, GCM_IV_BYTES), StandardCharsets.UTF_8);
	}
}
