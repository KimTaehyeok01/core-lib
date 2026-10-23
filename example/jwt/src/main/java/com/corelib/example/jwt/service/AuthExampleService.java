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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 회원 로그인 서비스.
 * <ul>
 *   <li>로그인 — 비밀번호 확인 후 {@link JwtUtils#issue} 로 액세스·리프레시 토큰</li>
 *   <li>갱신(회전) — 리프레시 토큰은 한 번만 쓴다. 쓰고 나면 새 쌍을 주고 옛 jti 는 무효</li>
 *   <li>재사용 감지 — 이미 쓴 리프레시 토큰이 또 오면 탈취로 보고 그 회원의 리프레시를 전부 폐기 (다시 로그인)</li>
 *   <li>로그아웃 — 리프레시 jti 폐기. 액세스 토큰은 만료(30분)까지 유효하다 — 짧게 두는 이유</li>
 * </ul>
 */
@Slf4j
@Service
public class AuthExampleService {

	private static final int PASSWORD_ITERATIONS = 600_000;
	private static final String USER = "USER";
	private static final String ADMIN = "ADMIN";

	private static final ErrorCode EMAIL_DUPLICATED = ErrorCode.of("MEMBER_EMAIL_DUPLICATED", "이미 가입된 이메일입니다.", 409);
	private static final ErrorCode LOGIN_FAILED = ErrorCode.of("LOGIN_FAILED", "이메일 또는 비밀번호가 올바르지 않습니다.", 401);
	private static final ErrorCode REFRESH_REJECTED = ErrorCode.of("REFRESH_REJECTED", "다시 로그인해 주세요.", 401);

	/** 실서비스는 회원 테이블 — 예제라 메모리 (키: 소문자 이메일) */
	private final Map<String, AuthExampleDto.Member> membersByEmail = new ConcurrentHashMap<>();
	/** 실서비스는 Redis·테이블 (회원별 유효한 리프레시 jti, TTL = 리프레시 유효시간) */
	private final Map<String, String> activeRefreshByMemberId = new ConcurrentHashMap<>();

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

	/** 이메일 없음·비밀번호 틀림을 같은 응답으로 — 가입 여부를 알려주지 않는다 */
	public JwtDto.TokenPair login(AuthExampleDto.LoginRequest request) {
		ValidateUtils.validate(request);
		AuthExampleDto.Member member = membersByEmail.get(key(request.getEmail()));
		if (member == null || !HmacUtils.isEqual(member.getPasswordHash(), hash(request.getPassword(), member.getPasswordSalt()))) {
			throw new CoreException(LOGIN_FAILED);
		}
		return issue(member);
	}

	public JwtDto.TokenPair refresh(AuthExampleDto.RefreshRequest request) {
		ValidateUtils.validate(request);
		JwtDto.Claims claims = JwtUtils.verifyRefreshToken(request.getRefreshToken());
		String active = activeRefreshByMemberId.get(claims.getSubject());
		if (active == null || !active.equals(claims.getTokenId())) {
			// 이미 회전된(또는 로그아웃한) 토큰 — 누군가 훔쳐 쓰는 중일 수 있어 남은 것까지 폐기
			activeRefreshByMemberId.remove(claims.getSubject());
			log.warn("리프레시 토큰 재사용 감지 - 전체 폐기. memberId={}", claims.getSubject());
			throw new CoreException(REFRESH_REJECTED);
		}
		AuthExampleDto.Member member = findById(claims.getSubject());    // 권한이 바뀌었을 수 있어 다시 읽는다
		return issue(member);
	}

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

	private JwtDto.TokenPair issue(AuthExampleDto.Member member) {
		JwtDto.TokenPair pair = JwtUtils.issue(member.getMemberId(), member.getRoles(), Map.of("nickname", member.getNickname()));
		activeRefreshByMemberId.put(member.getMemberId(), pair.getRefreshTokenId());
		return pair;
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
