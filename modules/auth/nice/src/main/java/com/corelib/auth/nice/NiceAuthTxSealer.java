// 인증 요청~결과 확인 사이의 거래 상태를 서버 저장 없이 넘기기 위한 봉인/해제 (AES-256-GCM)
package com.corelib.auth.nice;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.crypto.AesUtils;
import com.corelib.crypto.Base64Utils;
import com.corelib.crypto.HmacUtils;
import com.corelib.lang.StringUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

/**
 * 거래번호(txNo) 봉인 — access_token·ticket·transaction_id 를 서버에 저장하지 않고 소비자에게 맡긴다.
 * <ul>
 *   <li>형식: base64url( 버전(1) || IV(12) || AES-256-GCM 암호문+태그 ), 버전 바이트는 AAD 로 묶어 바꿔치기를 막는다</li>
 *   <li>봉인키: HmacSHA256(client_secret, 용도 문자열) — 설정을 하나 더 두지 않고 파생한다</li>
 *   <li>위변조·형식 오류는 원인을 숨기고 한 가지(TX_INVALID)로만 알린다</li>
 * </ul>
 * @implNote 1회성 보장(재사용 차단)은 무저장 설계라 범위 밖이다. NICE 가 같은 거래의 결과 재요청을 3033 으로 막는다
 */
final class NiceAuthTxSealer {

	private static final byte VERSION = 1;
	private static final byte[] AAD = {VERSION};
	private static final int IV_BYTES = AesUtils.Mode.GCM.getIvLength();
	private static final int GCM_TAG_BYTES = 16;
	private static final String KEY_PURPOSE = "corelib:auth-nice:tx-seal:v1";

	private NiceAuthTxSealer() {
	}

	/** 봉인 대상 거래 상태 — 코어 내부 표현이며 소비자에게 내용이 보이지 않는다 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record TxState(String accessToken, String ticket, Integer iterators, String transactionId, String requestNo,
				   Long issuedAt, Long tokenExpiresAt) {
	}

	static String seal(TxState state, String clientSecret, ObjectMapper mapper) {
		try {
			byte[] iv = AesUtils.generateIv(AesUtils.Mode.GCM);
			byte[] encrypted = AesUtils.encrypt(mapper.writeValueAsBytes(state), sealKey(clientSecret), iv, AesUtils.Mode.GCM, AAD);
			return Base64Utils.encodeUrl(ByteBuffer.allocate(1 + iv.length + encrypted.length).put(VERSION).put(iv).put(encrypted).array());
		} catch (IOException | CoreException e) {
			// 원인 메시지에 토큰 값이 실릴 수 있어 cause 를 남기지 않는다.
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "NICE 인증 거래 봉인에 실패했습니다.");
		}
	}

	/**
	 * 거래번호를 해제하고 유효시간을 확인한다.
	 *
	 * @throws CoreException TX_INVALID(위변조·형식) / TX_EXPIRED(유효시간 또는 토큰 만료)
	 */
	static TxState unseal(String txNo, String clientSecret, Duration ttl, ObjectMapper mapper) {
		TxState state = open(txNo, clientSecret, mapper);
		long now = System.currentTimeMillis();
		if (state.issuedAt() + ttl.toMillis() < now || now >= state.tokenExpiresAt()) {
			throw new CoreException(NiceAuthError.TX_EXPIRED).addErrorLogData("requestNo", state.requestNo());
		}
		return state;
	}

	private static TxState open(String txNo, String clientSecret, ObjectMapper mapper) {
		TxState state;
		try {
			byte[] sealed = Base64Utils.decodeUrl(txNo);
			if (sealed.length <= 1 + IV_BYTES + GCM_TAG_BYTES || sealed[0] != VERSION) {
				throw invalid();
			}
			byte[] plain = AesUtils.decrypt(Arrays.copyOfRange(sealed, 1 + IV_BYTES, sealed.length), sealKey(clientSecret),
				Arrays.copyOfRange(sealed, 1, 1 + IV_BYTES), AesUtils.Mode.GCM, AAD);
			state = mapper.readValue(plain, TxState.class);
		} catch (IOException | CoreException e) {
			// GCM 태그 불일치 = 위변조. 어떤 단계에서 실패했는지 노출하지 않는다.
			throw invalid();
		}
		if (state == null || state.issuedAt() == null || state.tokenExpiresAt() == null || state.iterators() == null
				|| StringUtils.isBlank(state.accessToken()) || StringUtils.isBlank(state.ticket())
				|| StringUtils.isBlank(state.transactionId()) || StringUtils.isBlank(state.requestNo())) {
			throw invalid();
		}
		return state;
	}

	/** HmacSHA256 출력 32 byte 를 그대로 AES-256 키로 쓴다 */
	private static byte[] sealKey(String clientSecret) {
		return HmacUtils.hmacSha256(clientSecret.getBytes(StandardCharsets.UTF_8), KEY_PURPOSE.getBytes(StandardCharsets.UTF_8));
	}

	private static CoreException invalid() {
		return new CoreException(NiceAuthError.TX_INVALID);
	}
}
