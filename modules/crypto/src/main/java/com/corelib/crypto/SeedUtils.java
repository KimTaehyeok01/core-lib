package com.corelib.crypto;

import com.corelib.common.exception.CoreException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.BufferedBlockCipher;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.crypto.engines.SEEDEngine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

/**
 * SEED(KISA 128bit 블록 암호, RFC 4269) 암복호화 — 국내 기관·금융 연동 규격 호환용.
 * <ul>
 *   <li>CBC + PKCS#7 패딩. 새로 만드는 데이터는 {@link AesUtils} GCM 을 쓴다 — SEED 는 무결성 검증이 없다</li>
 *   <li>{@link #encryptWithIv} 는 매번 새 IV 를 만들어 암호문 앞에 붙인다</li>
 *   <li>ECB 는 같은 평문 블록이 같은 암호문이 돼 패턴이 드러난다 — 상대 규격이 ECB 일 때만 쓴다</li>
 *   <li>BouncyCastle 엔진을 직접 쓴다 — JVM 전역 보안 Provider 를 등록하지 않는다</li>
 * </ul>
 */
public final class SeedUtils {

	private static final int KEY_BYTES = 16;
	private static final int IV_BYTES = 16;
	private static final SecureRandom RANDOM = new SecureRandom();

	private SeedUtils() {
	}

	/** 무작위 16 byte 키 */
	public static byte[] generateKey() {
		byte[] key = new byte[KEY_BYTES];
		RANDOM.nextBytes(key);
		return key;
	}

	/** SEED-CBC — iv 는 16 byte */
	public static byte[] encryptCbc(byte[] plain, byte[] key, byte[] iv) {
		requireIv(iv);
		return run(true, CBCBlockCipher.newInstance(new SEEDEngine()), new ParametersWithIV(keyOf(key), iv), plain);
	}

	/**
	 * @throws CoreException DECRYPT_FAILED(키·IV 불일치, 패딩 오류 — 원인은 구분하지 않는다)
	 */
	public static byte[] decryptCbc(byte[] cipherText, byte[] key, byte[] iv) {
		requireIv(iv);
		return run(false, CBCBlockCipher.newInstance(new SEEDEngine()), new ParametersWithIV(keyOf(key), iv), cipherText);
	}

	/** 새 IV 로 SEED-CBC 암호화 → IV(16) || 암호문 */
	public static byte[] encryptWithIv(byte[] plain, byte[] key) {
		byte[] iv = new byte[IV_BYTES];
		RANDOM.nextBytes(iv);
		byte[] cipherText = encryptCbc(plain, key, iv);
		return ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText).array();
	}

	/** {@link #encryptWithIv} 결과 복호화 */
	public static byte[] decryptWithIv(byte[] ivAndCipherText, byte[] key) {
		if (ivAndCipherText == null || ivAndCipherText.length <= IV_BYTES) {
			throw new CoreException(CryptoError.DECRYPT_FAILED);
		}
		return decryptCbc(Arrays.copyOfRange(ivAndCipherText, IV_BYTES, ivAndCipherText.length), key,
			Arrays.copyOfRange(ivAndCipherText, 0, IV_BYTES));
	}

	/** SEED-ECB — 상대 규격이 ECB 일 때만 */
	public static byte[] encryptEcb(byte[] plain, byte[] key) {
		return run(true, new SEEDEngine(), keyOf(key), plain);
	}

	/** SEED-ECB 복호화 */
	public static byte[] decryptEcb(byte[] cipherText, byte[] key) {
		return run(false, new SEEDEngine(), keyOf(key), cipherText);
	}

	private static byte[] run(boolean isEncrypt, BlockCipher engine, org.bouncycastle.crypto.CipherParameters parameters, byte[] input) {
		if (input == null) {
			throw new CoreException(isEncrypt ? CryptoError.ENCRYPT_FAILED : CryptoError.DECRYPT_FAILED, "입력값이 없습니다.");
		}
		BufferedBlockCipher cipher = new PaddedBufferedBlockCipher(engine, new PKCS7Padding());
		cipher.init(isEncrypt, parameters);
		byte[] output = new byte[cipher.getOutputSize(input.length)];
		try {
			int length = cipher.processBytes(input, 0, input.length, output, 0);
			length += cipher.doFinal(output, length);
			return Arrays.copyOf(output, length);
		} catch (CryptoException | RuntimeException e) {
			if (isEncrypt) {
				throw new CoreException(CryptoError.ENCRYPT_FAILED, "SEED 암호화에 실패했습니다.", e);
			}
			throw new CoreException(CryptoError.DECRYPT_FAILED);
		}
	}

	private static KeyParameter keyOf(byte[] key) {
		if (key == null || key.length != KEY_BYTES) {
			throw new CoreException(CryptoError.INVALID_KEY, "SEED 키는 16 byte 여야 합니다.");
		}
		return new KeyParameter(key);
	}

	private static void requireIv(byte[] iv) {
		if (iv == null || iv.length != IV_BYTES) {
			throw new CoreException(CryptoError.INVALID_KEY, "SEED IV 는 16 byte 여야 합니다.");
		}
	}
}
