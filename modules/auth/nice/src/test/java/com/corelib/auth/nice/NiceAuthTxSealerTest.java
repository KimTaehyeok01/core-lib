// NiceAuthTxSealer 봉인 왕복·만료·다른 키 검증과 NiceAuthProperty 설정 검증
package com.corelib.auth.nice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NiceAuthTxSealerTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String SIGN = "ns-test";

	private static NiceAuthTxSealer.TxState state(long issuedAt, long tokenExpiresAt) {
		return new NiceAuthTxSealer.TxState("at-1", "ticket-1", 66, "tx-1", "20261018100000000ABCDEFGHIJKLM",
			issuedAt, tokenExpiresAt);
	}

	@Test
	@DisplayName("봉인 → 해제 왕복")
	void roundTrip() {
		long now = System.currentTimeMillis();
		String txNo = NiceAuthTxSealer.seal(state(now, now + 60_000), SIGN, MAPPER);

		NiceAuthTxSealer.TxState opened = NiceAuthTxSealer.unseal(txNo, SIGN, Duration.ofMinutes(10), MAPPER);

		assertThat(opened.transactionId()).isEqualTo("tx-1");
		assertThat(opened.iterators()).isEqualTo(66);
		assertThat(txNo).doesNotContain("at-1", "ticket-1");
	}

	@Test
	@DisplayName("유효시간 초과·토큰 만료 - TX_EXPIRED")
	void expired() {
		long now = System.currentTimeMillis();
		String oldTx = NiceAuthTxSealer.seal(state(now - Duration.ofMinutes(11).toMillis(), now + 60_000), SIGN, MAPPER);
		String expiredToken = NiceAuthTxSealer.seal(state(now, now - 1), SIGN, MAPPER);

		assertThatThrownBy(() -> NiceAuthTxSealer.unseal(oldTx, SIGN, Duration.ofMinutes(10), MAPPER))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(NiceAuthError.TX_EXPIRED);
		assertThatThrownBy(() -> NiceAuthTxSealer.unseal(expiredToken, SIGN, Duration.ofMinutes(10), MAPPER))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(NiceAuthError.TX_EXPIRED);
	}

	@Test
	@DisplayName("다른 client-secret 으로는 열 수 없음 - TX_INVALID")
	void otherSecret() {
		long now = System.currentTimeMillis();
		String txNo = NiceAuthTxSealer.seal(state(now, now + 60_000), SIGN, MAPPER);

		assertThatThrownBy(() -> NiceAuthTxSealer.unseal(txNo, "other", Duration.ofMinutes(10), MAPPER))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(NiceAuthError.TX_INVALID);
		assertThatThrownBy(() -> NiceAuthTxSealer.unseal("not-base64-!!", SIGN, Duration.ofMinutes(10), MAPPER))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(NiceAuthError.TX_INVALID);
	}

	@Test
	@DisplayName("설정 검증 - tx-ttl 10분 초과, http base-url, return-url 누락 거부")
	void propertyValidate() {
		NiceAuthProperty property = new NiceAuthProperty();
		property.setClientId("nice-client");
		property.setClientSecret(SIGN);
		property.setReturnUrl("https://example.com/return");
		property.validate();

		property.setTxTtl(Duration.ofMinutes(11));
		assertThatThrownBy(property::validate)
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER);

		property.setTxTtl(Duration.ofMinutes(10));
		property.setBaseUrl("http://auth.niceid.co.kr");
		assertThatThrownBy(property::validate)
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER);

		property.setBaseUrl(NiceAuthProperty.DEFAULT_BASE_URL);
		property.setReturnUrl(null);
		assertThatThrownBy(property::validate)
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER);
	}
}
