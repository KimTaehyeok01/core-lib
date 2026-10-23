package com.corelib.auth.otp;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP (RFC 6238) — Google Authenticator·Microsoft Authenticator 등과 호환.
 * <ul>
 *   <li>비밀키는 20byte 난수(SecureRandom)를 Base32 로 — 호출측이 회원별로 암호화(AesUtils)해 저장한다</li>
 *   <li>검증은 ±window 단계 허용, 비교는 상수 시간</li>
 *   <li>같은 코드 재사용 방지: 검증 결과의 timeStep 을 저장해 다음 검증에 lastUsedTimeStep 으로 넘긴다</li>
 *   <li>무차별 대입(6자리 = 100만 가지) 제한은 호출측이 한다 — 예: 5회 실패 시 잠금</li>
 * </ul>
 */
public final class OtpUtils {

	private static final int SECRET_BYTES = 20;
	private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
	private static final int[] POW10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};
	private static final SecureRandom RANDOM = new SecureRandom();

	private static volatile OtpProperty property = new OtpProperty();

	private OtpUtils() {
	}

	/** 기동 시 {@link OtpInitialize} 가 yml 값으로 바꾼다 — 호출 전에는 기본값(SHA1·6자리·30초) */
	static void initialize(OtpProperty initializeProperty) {
		if (initializeProperty.getDigits() < 6 || initializeProperty.getDigits() > 8) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "auth.otp.digits 는 6~8 이어야 합니다.");
		}
		if (initializeProperty.getPeriod().toSeconds() < 1 || initializeProperty.getWindow() < 0 || initializeProperty.getWindow() > 5) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "auth.otp.period·window 설정이 올바르지 않습니다.");
		}
		property = initializeProperty;
	}

	/** 새 비밀키 + QR 용 URI — 회원이 2단계 인증을 켤 때 */
	public static OtpDto.Registration register(String accountName) {
		String secret = generateSecret();
		return OtpDto.Registration.builder().secret(secret).otpauthUri(otpauthUri(accountName, secret)).build();
	}

	/** Base32 비밀키 (32자) */
	public static String generateSecret() {
		byte[] bytes = new byte[SECRET_BYTES];
		RANDOM.nextBytes(bytes);
		return base32Encode(bytes);
	}

	/** otpauth://totp/{issuer}:{account}?secret=...&issuer=...&algorithm=...&digits=...&period=... */
	public static String otpauthUri(String accountName, String secret) {
		if (StringUtils.isBlank(accountName)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "OTP 계정 이름이 비어 있습니다.");
		}
		OtpProperty current = property;
		String label = encode(current.getIssuer()) + ":" + encode(accountName.trim());
		return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + encode(current.getIssuer())
			+ "&algorithm=" + current.getAlgorithm().name() + "&digits=" + current.getDigits() + "&period=" + current.getPeriod().toSeconds();
	}

	/** 지금 시각의 코드 — 테스트·서버 간 검증용 (사용자에게 보여주지 않는다) */
	public static String generateCode(String secret, Instant at) {
		OtpProperty current = property;
		return code(base32Decode(secret), timeStepOf(at, current), current);
	}

	/** 코드 검증 (재사용 검사 없음) */
	public static OtpDto.VerifyResult verify(String secret, String code) {
		return verify(secret, code, -1);
	}

	/**
	 * 코드 검증 — ±window 단계, 이미 쓴 단계(lastUsedTimeStep 이하)의 코드는 거부.
	 * @param lastUsedTimeStep 지난번 성공한 timeStep (처음이면 -1)
	 */
	public static OtpDto.VerifyResult verify(String secret, String code, long lastUsedTimeStep) {
		OtpProperty current = property;
		String input = code == null ? "" : code.replace(" ", "").trim();
		if (input.length() != current.getDigits() || !input.chars().allMatch(Character::isDigit)) {
			return invalid();
		}
		byte[] key = base32Decode(secret);
		long now = timeStepOf(Instant.now(), current);
		long matched = -1;
		for (long step = now - current.getWindow(); step <= now + current.getWindow(); step++) {
			// 상수 시간 비교 + 맞아도 끝까지 돌아 응답 시간으로 위치가 드러나지 않게
			if (MessageDigest.isEqual(code(key, step, current).getBytes(StandardCharsets.US_ASCII), input.getBytes(StandardCharsets.US_ASCII))) {
				matched = step;
			}
		}
		if (matched < 0 || matched <= lastUsedTimeStep) {
			return invalid();
		}
		return OtpDto.VerifyResult.builder().isValid(true).timeStep(matched).build();
	}

	/** RFC 4226 HOTP — HMAC(counter) → dynamic truncation → 10^digits */
	static String code(byte[] key, long timeStep, OtpProperty current) {
		try {
			Mac mac = Mac.getInstance(current.getAlgorithm().macName());
			mac.init(new SecretKeySpec(key, current.getAlgorithm().macName()));
			byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(timeStep).array());
			int offset = hash[hash.length - 1] & 0x0F;
			int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
				| ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
			return String.format("%0" + current.getDigits() + "d", binary % POW10[current.getDigits()]);
		} catch (GeneralSecurityException e) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "OTP 계산에 실패했습니다.", e);
		}
	}

	private static long timeStepOf(Instant at, OtpProperty current) {
		return Math.floorDiv(at.getEpochSecond(), current.getPeriod().toSeconds());
	}

	private static OtpDto.VerifyResult invalid() {
		return OtpDto.VerifyResult.builder().isValid(false).timeStep(-1).build();
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	/** RFC 4648 Base32 (패딩 없음) */
	static String base32Encode(byte[] bytes) {
		StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
		int buffer = 0;
		int bits = 0;
		for (byte value : bytes) {
			buffer = (buffer << 8) | (value & 0xFF);
			bits += 8;
			while (bits >= 5) {
				out.append(BASE32[(buffer >>> (bits - 5)) & 0x1F]);
				bits -= 5;
			}
		}
		if (bits > 0) {
			out.append(BASE32[(buffer << (5 - bits)) & 0x1F]);
		}
		return out.toString();
	}

	/** 대소문자·공백·패딩(=) 무시 — 사용자가 손으로 옮겨 적은 값도 받는다 */
	static byte[] base32Decode(String value) {
		if (StringUtils.isBlank(value)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "OTP 비밀키가 비어 있습니다.");
		}
		String text = value.replace(" ", "").replace("=", "").toUpperCase();
		ByteBuffer out = ByteBuffer.allocate(text.length() * 5 / 8);
		int buffer = 0;
		int bits = 0;
		for (char ch : text.toCharArray()) {
			int index = ch >= 'A' && ch <= 'Z' ? ch - 'A' : ch >= '2' && ch <= '7' ? ch - '2' + 26 : -1;
			if (index < 0) {
				throw new CoreException(CommonErrorCode.BAD_REQUEST, "OTP 비밀키 형식이 올바르지 않습니다.");
			}
			buffer = (buffer << 5) | index;
			bits += 5;
			if (bits >= 8) {
				out.put((byte) (buffer >>> (bits - 8)));
				bits -= 8;
			}
		}
		return out.array();
	}
}
