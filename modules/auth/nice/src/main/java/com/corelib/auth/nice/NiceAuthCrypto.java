// NICE 인증 결과 키 유도·무결성 검증·복호화 (공식 가이드 '3. 중요 정보 보안' 규칙)
package com.corelib.auth.nice;

import com.corelib.common.exception.CoreException;
import com.corelib.crypto.AesUtils;
import com.corelib.crypto.Base64Utils;
import com.corelib.crypto.HmacUtils;
import com.corelib.crypto.KdfUtils;
import java.nio.charset.StandardCharsets;

/**
 * NICE 가 결과 요청에 쓴 access_token 의 ticket·iterators 와 transaction_id 로 키를 만든다.
 * <ul>
 *   <li>키 유도: PBKDF2WithHmacSHA256(password=ticket, salt=transaction_id, iterations=iterators, 512bit) → base64url(무패딩)</li>
 *   <li>키는 바이트가 아니라 <b>인코딩된 문자열</b>에서 자른다 — 대칭키 [0,32), 무결성키 [48,80)</li>
 *   <li>무결성: base64url(HmacSHA256(무결성키, 받은 enc_data 문자열 그대로)) == integrity_value</li>
 *   <li>복호화: AES-256-GCM(태그 128bit), base64url 디코딩한 enc_data 앞 16바이트가 IV</li>
 * </ul>
 * @implNote 가이드 본문 일부는 IV 를 디코딩 전 문자열에서 자르라고 적었지만, 가이드의 모든 언어 복호화 코드는 디코딩 후 바이트에서 자른다 — 코드를 따른다
 */
final class NiceAuthCrypto {

	private static final int KDF_KEY_BITS = 512;
	private static final int AES_KEY_END = 32;
	private static final int HMAC_KEY_START = 48;
	private static final int HMAC_KEY_END = 80;
	/** NICE 규격 GCM IV 는 16 byte (crypto 기본 12 byte 와 다름) */
	private static final int IV_BYTES = 16;

	private NiceAuthCrypto() {
	}

	/** 키 문자열(86자)을 유도한다 */
	static String deriveKeyString(String ticket, String transactionId, int iterators) {
		try {
			return Base64Utils.encodeUrl(KdfUtils.pbkdf2Sha256(ticket.toCharArray(),
				transactionId.getBytes(StandardCharsets.UTF_8), iterators, KDF_KEY_BITS));
		} catch (CoreException e) {
			throw new CoreException(NiceAuthError.DECRYPT_FAILED, "NICE 인증 결과 키 유도에 실패했습니다.", e);
		}
	}

	/**
	 * 무결성을 검증한다 — 복호화보다 먼저 호출한다(변조된 암호문을 복호화기에 넣지 않는다).
	 *
	 * @throws CoreException INTEGRITY_FAILED
	 */
	static void verifyIntegrity(String encData, String integrityValue, String keyString) {
		byte[] hmacKey = keyString.substring(HMAC_KEY_START, HMAC_KEY_END).getBytes(StandardCharsets.US_ASCII);
		// 디코딩한 바이트가 아니라 받은 enc_data 문자열 원문이 HMAC 입력이다.
		String expected = Base64Utils.encodeUrl(HmacUtils.hmacSha256(hmacKey, encData.getBytes(StandardCharsets.US_ASCII)));
		if (integrityValue == null || !HmacUtils.isEqual(expected, integrityValue.trim())) {
			throw new CoreException(NiceAuthError.INTEGRITY_FAILED, "NICE 인증 결과 무결성 검증에 실패했습니다.");
		}
	}

	/**
	 * 인증 결과를 복호화해 평문 JSON 을 돌려준다.
	 *
	 * @throws CoreException DECRYPT_FAILED — 원인 메시지에 암호문이 실리지 않게 cause 를 남기지 않는다
	 */
	static String decrypt(String encData, String keyString) {
		byte[] key = keyString.substring(0, AES_KEY_END).getBytes(StandardCharsets.US_ASCII);
		try {
			byte[] plain = AesUtils.decryptWithIv(Base64Utils.decodeUrl(encData), key, AesUtils.Mode.GCM, IV_BYTES);
			return new String(plain, StandardCharsets.UTF_8);
		} catch (CoreException e) {
			throw new CoreException(NiceAuthError.DECRYPT_FAILED, "NICE 인증 결과 복호화에 실패했습니다.");
		}
	}
}
