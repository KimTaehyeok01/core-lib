package com.corelib.crypto;

import com.corelib.common.exception.CoreException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * RSA 암복호화·서명.
 * <ul>
 *   <li>암호화 기본은 OAEP(SHA-256, MGF1-SHA-256) — PKCS#1 v1.5 는 벤더 규격 호환용으로만 쓴다</li>
 *   <li>서명은 SHA256withRSA</li>
 *   <li>RSA 는 키 길이보다 짧은 데이터(2048bit OAEP-SHA256 이면 190 byte)만 암호화된다 — 긴 데이터는 AES 키를 RSA 로 감싼다</li>
 *   <li>키 길이는 2048bit 이상만 만든다</li>
 * </ul>
 */
public final class RsaUtils {

	private static final int MIN_KEY_BITS = 2048;
	private static final String SIGNATURE = "SHA256withRSA";
	private static final OAEPParameterSpec OAEP_SHA256 =
		new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

	private RsaUtils() {
	}

	/** 암호화 패딩 */
	public enum Padding {
		/** RSA/ECB/OAEPPadding + SHA-256 — 기본 */
		OAEP_SHA256("RSA/ECB/OAEPPadding"),
		/** RSA/ECB/PKCS1Padding — 기존 연동(벤더 규격) 호환용 */
		PKCS1_V1_5("RSA/ECB/PKCS1Padding");

		private final String transformation;

		Padding(String transformation) {
			this.transformation = transformation;
		}
	}

	/** @param keyBits 2048 이상 (권장 2048·3072·4096) */
	public static KeyPair generateKeyPair(int keyBits) {
		if (keyBits < MIN_KEY_BITS) {
			throw new CoreException(CryptoError.INVALID_KEY, "RSA 키는 " + MIN_KEY_BITS + "bit 이상이어야 합니다.");
		}
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(keyBits);
			return generator.generateKeyPair();
		} catch (GeneralSecurityException e) {
			throw new CoreException(CryptoError.INVALID_KEY, "RSA 키 생성에 실패했습니다.", e);
		}
	}

	/** OAEP-SHA256 로 암호화 */
	public static byte[] encrypt(byte[] plain, PublicKey publicKey) {
		return encrypt(plain, publicKey, Padding.OAEP_SHA256);
	}

	/**
	 * @throws CoreException INVALID_KEY(키 없음·종류 다름) / ENCRYPT_FAILED(데이터가 키 길이보다 김)
	 */
	public static byte[] encrypt(byte[] plain, PublicKey publicKey, Padding padding) {
		requireKey(publicKey);
		try {
			Cipher cipher = cipher(padding, Cipher.ENCRYPT_MODE, publicKey);
			return cipher.doFinal(plain);
		} catch (InvalidKeyException e) {
			throw new CoreException(CryptoError.INVALID_KEY, "RSA 공개키가 올바르지 않습니다.", e);
		} catch (GeneralSecurityException e) {
			throw new CoreException(CryptoError.ENCRYPT_FAILED, "RSA 암호화에 실패했습니다. 데이터가 키 길이보다 긴지 확인하세요.", e);
		}
	}

	/** OAEP-SHA256 로 복호화 */
	public static byte[] decrypt(byte[] cipherText, PrivateKey privateKey) {
		return decrypt(cipherText, privateKey, Padding.OAEP_SHA256);
	}

	/**
	 * @throws CoreException INVALID_KEY / DECRYPT_FAILED(키·패딩 불일치, 위변조 — 원인은 구분하지 않는다)
	 */
	public static byte[] decrypt(byte[] cipherText, PrivateKey privateKey, Padding padding) {
		requireKey(privateKey);
		try {
			Cipher cipher = cipher(padding, Cipher.DECRYPT_MODE, privateKey);
			return cipher.doFinal(cipherText);
		} catch (InvalidKeyException e) {
			throw new CoreException(CryptoError.INVALID_KEY, "RSA 개인키가 올바르지 않습니다.", e);
		} catch (GeneralSecurityException e) {
			throw new CoreException(CryptoError.DECRYPT_FAILED, CryptoError.DECRYPT_FAILED.message(), e);
		}
	}

	/** SHA256withRSA 서명 */
	public static byte[] sign(byte[] data, PrivateKey privateKey) {
		requireKey(privateKey);
		try {
			Signature signature = Signature.getInstance(SIGNATURE);
			signature.initSign(privateKey);
			signature.update(data);
			return signature.sign();
		} catch (GeneralSecurityException e) {
			throw new CoreException(CryptoError.INVALID_KEY, "RSA 서명에 실패했습니다.", e);
		}
	}

	/**
	 * @return 서명이 맞으면 true, 다르거나 형식이 깨졌으면 false
	 * @throws CoreException INVALID_KEY(키 자체가 잘못됨)
	 */
	public static boolean verify(byte[] data, byte[] signatureBytes, PublicKey publicKey) {
		requireKey(publicKey);
		if (data == null || signatureBytes == null) {
			return false;
		}
		try {
			Signature signature = Signature.getInstance(SIGNATURE);
			signature.initVerify(publicKey);
			signature.update(data);
			return signature.verify(signatureBytes);
		} catch (SignatureException e) {
			return false;
		} catch (GeneralSecurityException e) {
			throw new CoreException(CryptoError.INVALID_KEY, "RSA 공개키가 올바르지 않습니다.", e);
		}
	}

	private static Cipher cipher(Padding padding, int mode, java.security.Key key) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance(padding.transformation);
		if (padding == Padding.OAEP_SHA256) {
			cipher.init(mode, key, OAEP_SHA256);
		} else {
			cipher.init(mode, key);
		}
		return cipher;
	}

	private static void requireKey(java.security.Key key) {
		if (key == null) {
			throw new CoreException(CryptoError.INVALID_KEY, "RSA 키가 없습니다.");
		}
	}
}
