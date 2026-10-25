package com.corelib.example.jwt.service;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.crypto.AesUtils;
import com.corelib.crypto.Base64Utils;
import com.corelib.crypto.HmacUtils;
import com.corelib.crypto.KdfUtils;
import com.corelib.example.jwt.dto.AuthExampleDto;
import com.corelib.jwt.JwtDto;
import com.corelib.jwt.JwtUtils;
import com.corelib.lang.DateTimeUtils;
import com.corelib.lang.MaskingUtils;
import com.corelib.lang.StringUtils;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 회원 로그인 서비스.
 * <ul>
 *   <li>로그인 — 비밀번호 확인 후 {@link JwtUtils#issue} 로 액세스·리프레시 토큰. 기기마다 따로 로그인할 수 있다</li>
 *   <li>로그인 제한 — 이메일당 5번 틀리면 5분 잠금(429). 없는 이메일도 같은 PBKDF2 를 계산하고 똑같이 잠근다 (가입 여부 노출 방지)</li>
 *   <li>갱신(회전) — 리프레시 토큰은 한 번만 쓴다. 쓰고 나면 새 쌍을 주고 옛 jti 는 무효</li>
 *   <li>재사용 감지 — 이미 쓴 리프레시 토큰이 또 오면 탈취로 보고 그 회원의 리프레시를 전부(모든 기기) 폐기 (다시 로그인)</li>
 *   <li>로그아웃 — 그 회원의 리프레시 jti 전부(모든 기기) 폐기. 액세스 토큰은 만료(30분)까지 유효하다 — 짧게 두는 이유</li>
 * </ul>
 */
@Slf4j
@Service
public class AuthExampleService {

	private static final int PASSWORD_ITERATIONS = 600_000;
	private static final int MAX_LOGIN_FAILS = 5;
	private static final Duration LOGIN_LOCK_TIME = Duration.ofMinutes(5);
	private static final String USER = "USER";
	private static final String ADMIN = "ADMIN";

	private static final ErrorCode EMAIL_DUPLICATED = ErrorCode.of("MEMBER_EMAIL_DUPLICATED", "이미 가입된 이메일입니다.", 409);
	private static final ErrorCode LOGIN_FAILED = ErrorCode.of("LOGIN_FAILED", "이메일 또는 비밀번호가 올바르지 않습니다.", 401);
	private static final ErrorCode LOGIN_LOCKED = ErrorCode.of("LOGIN_LOCKED", "로그인 시도가 너무 많습니다. 5분 후 다시 시도해 주세요.", 429);
	private static final ErrorCode REFRESH_REJECTED = ErrorCode.of("REFRESH_REJECTED", "다시 로그인해 주세요.", 401);

	/** 실서비스는 회원 테이블 — 예제라 메모리 (키: 소문자 이메일) */
	private final Map<String, AuthExampleDto.Member> membersByEmail = new ConcurrentHashMap<>();
	/**
	 * 실서비스는 Redis·테이블 (회원별 유효한 리프레시 jti — 기기마다 1개, TTL = 리프레시 유효시간).
	 * 회전·폐기는 compute 한 번으로 원자 처리 — 실서비스는 DB 조건부 DELETE(WHERE jti = ?) 가 1건일 때만 발급해 같은 원자성 확보
	 */
	private final Map<String, Set<String>> activeRefreshByMemberId = new ConcurrentHashMap<>();
	/** 키: 소문자 이메일. 증가·잠금은 compute 로 원자 처리 — 실서비스는 DB 조건부 UPDATE·Redis INCR 로 같은 원자성 확보 */
	private final Map<String, AuthExampleDto.LoginFailure> loginFailuresByEmail = new ConcurrentHashMap<>();
	/** 없는 이메일 로그인에도 같은 PBKDF2 를 계산할 salt — 응답 시간으로 가입 여부를 알 수 없게 */
	private final String dummySalt = Base64Utils.encodeUrl(AesUtils.generateKey(16));

	public AuthExampleService(@Value("${example.admin.email:}") String adminEmail, @Value("${example.admin.password:}") String adminPassword) {
		if (StringUtils.isNotBlank(adminEmail) && StringUtils.isNotBlank(adminPassword)) {
			save(adminEmail, adminPassword, "관리자", List.of(USER, ADMIN));
			log.info("관리자 계정 생성. email={}", MaskingUtils.email(adminEmail));
		}
	}

	public AuthExampleDto.MemberResponse signup(AuthExampleDto.SignupRequest request) {
		ValidateUtils.validate(request);
		return toResponse(save(request.getEmail(), request.getPassword(), request.getNickname().trim(), List.of(USER)), false);
	}

	/** 이메일 없음·비밀번호 틀림을 같은 응답·같은 계산량으로 — 가입 여부를 알려주지 않는다 */
	public JwtDto.TokenPair login(AuthExampleDto.LoginRequest request) {
		ValidateUtils.validate(request);
		String key = key(request.getEmail());
		purgeExpiredLoginFailures();    // 로그인 시도마다 — 공격 흔적만 쌓이고 지워지지 않던 맵을 간결하게 유지
		countLoginAttempt(key);
		AuthExampleDto.Member member = membersByEmail.get(key);
		String actual = hash(request.getPassword(), member != null ? member.getPasswordSalt() : dummySalt);
		if (member == null || !HmacUtils.isEqual(member.getPasswordHash(), actual)) {
			throw new CoreException(LOGIN_FAILED);
		}
		loginFailuresByEmail.remove(key);
		return issue(member);
	}

	/**
	 * 잠금이 풀렸고 상한에 닿지 않은 흔적을 지운다 — 성공·실패 시점 모두 호출돼 메모리가 영원히 자라지 않게 한다.
	 * 실서비스는 Redis TTL 로 자동 만료되므로 이 정리는 메모리 저장소 전용.
	 */
	private void purgeExpiredLoginFailures() {
		LocalDateTime now = DateTimeUtils.now();
		loginFailuresByEmail.values().removeIf(failure ->
			failure.getLockExpiresAt() != null && !now.isBefore(failure.getLockExpiresAt()));
	}

	/**
	 * 옛 jti 를 지우는 데 성공한 요청만 새 jti 를 등록한다 (CAS) — 같은 리프레시가 동시에 와도 1건만 성공.
	 * 지울 jti 가 없으면 이미 회전된(또는 로그아웃한) 토큰 — 누군가 훔쳐 쓰는 중일 수 있어 그 회원 전체 폐기
	 */
	public JwtDto.TokenPair refresh(AuthExampleDto.RefreshRequest request) {
		ValidateUtils.validate(request);
		JwtDto.Claims claims = JwtUtils.verifyRefreshToken(request.getRefreshToken());
		AuthExampleDto.Member member = findById(claims.getSubject());    // 권한이 바뀌었을 수 있어 다시 읽는다
		JwtDto.TokenPair pair = newPair(member);    // 서명은 compute 밖에서 — 회전에 실패하면 버린다 (응답에 나가지 않음)

		AtomicBoolean isRotated = new AtomicBoolean();
		activeRefreshByMemberId.computeIfPresent(member.getMemberId(), (memberId, jtis) -> {
			if (!jtis.remove(claims.getTokenId())) {
				return null;
			}
			jtis.add(pair.getRefreshTokenId());
			isRotated.set(true);
			return jtis;
		});
		if (!isRotated.get()) {
			log.warn("리프레시 토큰 재사용 감지 - 전체 폐기. memberId={}", claims.getSubject());
			throw new CoreException(REFRESH_REJECTED);
		}
		return pair;
	}

	/** 모든 기기 로그아웃 — 기기별로 하려면 리프레시 토큰을 받아 그 jti 만 지운다 */
	public void logout(String memberId) {
		activeRefreshByMemberId.remove(memberId);
	}

	public AuthExampleDto.MemberResponse me(JwtDto.Claims claims) {
		return toResponse(findById(claims.getSubject()), false);
	}

	/** 관리자 — 가입순, 이메일 마스킹 */
	public List<AuthExampleDto.MemberResponse> members() {
		return membersByEmail.values().stream()
							 .sorted(Comparator.comparing(AuthExampleDto.Member::getJoinedAt))
							 .map(member -> toResponse(member, true))
							 .toList();
	}

	/** 로그인 — 기존 기기의 jti 는 그대로 두고 새 기기 jti 를 더한다 */
	private JwtDto.TokenPair issue(AuthExampleDto.Member member) {
		JwtDto.TokenPair pair = newPair(member);
		activeRefreshByMemberId.compute(member.getMemberId(), (memberId, jtis) -> {
			Set<String> active = jtis != null ? jtis : ConcurrentHashMap.newKeySet();
			active.add(pair.getRefreshTokenId());
			return active;
		});
		return pair;
	}

	private static JwtDto.TokenPair newPair(AuthExampleDto.Member member) {
		return JwtUtils.issue(member.getMemberId(), member.getRoles(), Map.of("nickname", member.getNickname()));
	}

	/** 비밀번호를 계산하기 전에 시도를 먼저 센다 — 동시에 몰려와도 잠금 전까지 최대 5번만 계산. 잠금 중이면 계산 없이 429 */
	private void countLoginAttempt(String key) {
		LocalDateTime now = DateTimeUtils.now();
		loginFailuresByEmail.compute(key, (k, failure) -> {
			if (failure != null && failure.getLockExpiresAt() != null && now.isBefore(failure.getLockExpiresAt())) {
				throw new CoreException(LOGIN_LOCKED);    // 예외면 기존 값 유지
			}
			int failCount = failure == null || failure.getLockExpiresAt() != null ? 1 : failure.getFailCount() + 1;    // 잠금이 풀렸으면 새로 센다
			return AuthExampleDto.LoginFailure.builder()
											  .failCount(failCount)
											  .lockExpiresAt(failCount >= MAX_LOGIN_FAILS ? now.plus(LOGIN_LOCK_TIME) : null)
											  .build();
		});
	}

	private AuthExampleDto.Member save(String email, String password, String nickname, List<String> roles) {
		byte[] salt = AesUtils.generateKey(16);
		String saltText = Base64Utils.encodeUrl(salt);
		AuthExampleDto.Member member = AuthExampleDto.Member.builder()
															.memberId(UUID.randomUUID().toString())
															.email(email.trim())
															.nickname(nickname)
															.passwordSalt(saltText)
															.passwordHash(hash(password, saltText))
															.roles(roles)
															.joinedAt(DateTimeUtils.now())
															.build();
		if (membersByEmail.putIfAbsent(key(email), member) != null) {
			throw new CoreException(EMAIL_DUPLICATED);
		}
		return member;
	}

	private AuthExampleDto.Member findById(String memberId) {
		return membersByEmail.values().stream().filter(member -> member.getMemberId().equals(memberId)).findFirst()
							 .orElseThrow(() -> new CoreException(CommonErrorCode.UNAUTHORIZED, "회원을 찾을 수 없습니다."));
	}

	private static AuthExampleDto.MemberResponse toResponse(AuthExampleDto.Member member, boolean isMasked) {
		return AuthExampleDto.MemberResponse.builder()
											.memberId(member.getMemberId())
											.email(isMasked ? MaskingUtils.email(member.getEmail()) : member.getEmail())
											.nickname(member.getNickname())
											.roles(member.getRoles())
											.joinedAt(DateTimeUtils.format(member.getJoinedAt()))
											.build();
	}

	private static String hash(String password, String saltText) {
		return Base64Utils.encodeUrl(KdfUtils.pbkdf2Sha256(password.toCharArray(), Base64Utils.decodeUrl(saltText), PASSWORD_ITERATIONS, 256));
	}

	private static String key(String email) {
		return StringUtils.defaultIfBlank(email, "").trim().toLowerCase(Locale.ROOT);
	}
}
