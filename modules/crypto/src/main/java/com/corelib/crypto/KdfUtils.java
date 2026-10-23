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
	 *
	 * @param keyBits 출력 길이(bit) — 8 의 배수
	 * @throws CoreException INVALID_KEY(입력값 오류)
	 */
	public static byte[] pbkdf2Sha256(char[] password, byte[] salt, int iterations, int keyBits) {
		if (password == null || salt == null || iterations <= 0 || keyBits <= 0 || keyBits % 8 != 0) {
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
