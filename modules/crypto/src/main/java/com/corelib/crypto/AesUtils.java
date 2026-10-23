package com.corelib.crypto;

import com.corelib.common.exception.CoreException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES 암복호화.
 * <ul>
 *   <li>새로 만드는 데이터는 GCM — 위변조를 태그로 검출한다. CBC 는 무결성이 없어 기존 연동(벤더 규격) 호환용으로만 쓴다</li>
 *   <li>같은 키로 IV 를 재사용하지 않는다 — {@link #encryptWithIv} 는 매번 새 IV 를 만들어 암호문 앞에 붙인다</li>
 *   <li>키는 16/24/32 byte(AES-128/192/256)만 받는다</li>
 *   <li>복호화 실패 원인(태그 불일치/패딩 오류)은 구분해서 알리지 않는다 — 패딩 오라클 공격 방지</li>
 * </ul>
 */
public final class AesUtils {

	private static final int GCM_TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();

	private AesUtils() {
	}

	/** 운용 방식과 기본 IV 길이 */
	public enum Mode {
		/** AES/GCM/NoPadding — 기본 IV 12 byte */
		GCM("AES/GCM/NoPadding", 12),
		/** AES/CBC/PKCS5Padding — IV 16 byte */
		CBC("AES/CBC/PKCS5Padding", 16);

		private final String transformation;
		private final int ivLength;

		Mode(String transformation, int ivLength) {
			this.transformation = transformation;
			this.ivLength = ivLength;
		}

		public int getIvLength() {
			return ivLength;
		}
	}

	/** 무작위 AES 키 — keyBytes 는 16/24/32 */
	public static byte[] generateKey(int keyBytes) {
		requireKeyLength(keyBytes);
		byte[] key = new byte[keyBytes];
		RANDOM.nextBytes(key);
		return key;
	}

	/** 운용 방식의 기본 길이로 무작위 IV */
	public static byte[] generateIv(Mode mode) {
		byte[] iv = new byte[mode.ivLength];
		RANDOM.nextBytes(iv);
		return iv;
	}

	/**
	 * @param aad GCM 추가 인증 데이터(암호화되지 않지만 위변조는 검출) — CBC 이거나 없으면 null
	 * @throws CoreException INVALID_KEY / ENCRYPT_FAILED
	 */
	public static byte[] encrypt(byte[] plain, byte[] key, byte[] iv, Mode mode, byte[] aad) {
		try {
			return cipher(Cipher.ENCRYPT_MODE, key, iv, mode, aad).doFinal(plain);
		} catch (GeneralSecurityException e) {
			throw new CoreException(CryptoError.ENCRYPT_FAILED, "AES 암호화에 실패했습니다.", e);
		}
	}

	/**
	 * @throws CoreException INVALID_KEY / DECRYPT_FAILED(키 불일치·위변조·형식 오류 — 원인은 숨긴다)
	 */
	public static byte[] decrypt(byte[] cipherText, byte[] key, byte[] iv, Mode mode, byte[] aad) {
		try {
			return cipher(Cipher.DECRYPT_MODE, key, iv, mode, aad).doFinal(cipherText);
		} catch (GeneralSecurityException e) {
			throw new CoreException(CryptoError.DECRYPT_FAILED);
		}
	}

	/** 새 IV 로 암호화해 {@code IV || 암호문} 을 돌려준다 — 저장·전송용 */
	public static byte[] encryptWithIv(byte[] plain, byte[] key, Mode mode) {
		byte[] iv = generateIv(mode);
		byte[] encrypted = encrypt(plain, key, iv, mode, null);
		return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
	}

	/**
	 * {@code IV || 암호문} 을 복호화한다.
	 *
	 * @param ivLength 앞에 붙은 IV 길이 — 벤더 규격이 기본값과 다를 때(예: GCM 16 byte) 지정
	 * @throws CoreException DECRYPT_FAILED
	 */
	public static byte[] decryptWithIv(byte[] ivAndCipherText, byte[] key, Mode mode, int ivLength) {
		int minimum = ivLength + (mode == Mode.GCM ? GCM_TAG_BITS / 8 : 16);
		if (ivAndCipherText == null || ivAndCipherText.length < minimum) {
			throw new CoreException(CryptoError.DECRYPT_FAILED);
		}
		return decrypt(Arrays.copyOfRange(ivAndCipherText, ivLength, ivAndCipherText.length), key,
			Arrays.copyOfRange(ivAndCipherText, 0, ivLength), mode, null);
	}

	private static Cipher cipher(int operation, byte[] key, byte[] iv, Mode mode, byte[] aad) throws GeneralSecurityException {
		if (key == null) {
			throw new CoreException(CryptoError.INVALID_KEY, "AES 키가 없습니다.");
		}
		requireKeyLength(key.length);
		if (iv == null || iv.length == 0) {
			throw new CoreException(CryptoError.INVALID_KEY, "AES IV 가 없습니다.");
		}
		Cipher cipher = Cipher.getInstance(mode.transformation);
		SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
		if (mode == Mode.GCM) {
			cipher.init(operation, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
			if (aad != null) {
				cipher.updateAAD(aad);
			}
		} else {
			cipher.init(operation, keySpec, new IvParameterSpec(iv));
		}
		return cipher;
	}

	private static void requireKeyLength(int keyBytes) {
		if (keyBytes != 16 && keyBytes != 24 && keyBytes != 32) {
			throw new CoreException(CryptoError.INVALID_KEY, "AES 키는 16/24/32 byte 여야 합니다.");
		}
	}
}
