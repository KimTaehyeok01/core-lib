package com.corelib.crypto;

import com.corelib.common.exception.CoreException;
import java.security.GeneralSecurityException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class KdfUtils {

	private KdfUtils() {
	}

	/**
	 * PBKDF2WithHmacSHA256 으로 키를 유도한다.
	 * <p>
	 * 비밀번호 저장은 OWASP 권고(PBKDF2-HMAC-SHA256 600,000회 이상, 사용자별 난수 salt 16byte 이상)를 따른다.
	 * 반복 횟수 하한은 강제하지 않는다 — 벤더 규격(NICE 인증 등)이 정한 값을 그대로 넣어야 하는 경우가 있다.
	 *
	 * @param salt 비어 있으면 안 된다 — 비밀번호용은 사용자별 난수 16byte 이상
	 * @param iterations 비밀번호용은 600,000 이상 권장
	 * @param keyBits 출력 길이(bit) — 8 의 배수
	 * @throws CoreException INVALID_KEY(null 비밀번호·null/빈 salt·0 이하 반복 횟수·잘못된 출력 길이)
	 */
	public static byte[] pbkdf2Sha256(char[] password, byte[] salt, int iterations, int keyBits) {
		if (password == null || salt == null || salt.length == 0 || iterations <= 0 || keyBits <= 0 || keyBits % 8 != 0) {
			throw new CoreException(CryptoError.INVALID_KEY, "PBKDF2 입력값이 올바르지 않습니다.");
		}
		PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyBits);
		try {
			return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
		} catch (GeneralSecurityException e) {
			throw new CoreException(CryptoError.INVALID_KEY, "PBKDF2 키 유도에 실패했습니다.", e);
		} finally {
			spec.clearPassword();
		}
	}
}
