package com.corelib.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.exception.CoreException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CryptoUtilsTest {

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}

	private static byte[] utf8(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	private static Object errorCodeOf(Throwable e) {
		return ((CoreException) e).getErrorCode();
	}

	@Test
	@DisplayName("SHA-256 표준 벡터 (\"abc\")")
	void sha256() {
		assertThat(HashUtils.sha256Hex("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	@Test
	@DisplayName("HMAC-SHA256 RFC 4231 테스트 케이스 2")
	void hmacSha256() {
		byte[] mac = HmacUtils.hmacSha256(utf8("Jefe"), utf8("what do ya want for nothing?"));

		assertThat(hex(mac)).isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
		assertThat(HmacUtils.isEqual(mac, mac.clone())).isTrue();
		assertThat(HmacUtils.isEqual("abc", "abd")).isFalse();
		assertThat(HmacUtils.isEqual(null, "a")).isFalse();
	}

	@Test
	@DisplayName("PBKDF2-HMAC-SHA256 RFC 7914 벡터 (passwd/salt/1회/64byte)")
	void pbkdf2() {
		byte[] derived = KdfUtils.pbkdf2Sha256("passwd".toCharArray(), utf8("salt"), 1, 512);

		assertThat(hex(derived)).isEqualTo("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"
			+ "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783");
		assertThatThrownBy(() -> KdfUtils.pbkdf2Sha256("p".toCharArray(), utf8("s"), 0, 256))
			.extracting(CryptoUtilsTest::errorCodeOf).isEqualTo(CryptoError.INVALID_KEY);
	}

	@Test
	@DisplayName("Base64 표준/URL-safe 왕복, URL 은 패딩 없음, 형식 오류는 INVALID_ENCODING")
	void base64() {
		byte[] data = {(byte) 0xfb, (byte) 0xff, 0x00};

		assertThat(Base64Utils.decode(Base64Utils.encode(data))).isEqualTo(data);
		assertThat(Base64Utils.encodeUrl(data)).isEqualTo("-_8A");
		assertThat(Base64Utils.decodeUrl("-_8A")).isEqualTo(data);
		assertThatThrownBy(() -> Base64Utils.decodeUrl("!!"))
			.extracting(CryptoUtilsTest::errorCodeOf).isEqualTo(CryptoError.INVALID_ENCODING);
	}

	@Test
	@DisplayName("AES-GCM 왕복, AAD·암호문 변조와 다른 키는 DECRYPT_FAILED")
	void aesGcm() {
		byte[] key = AesUtils.generateKey(32);
		byte[] iv = AesUtils.generateIv(AesUtils.Mode.GCM);
		byte[] aad = {1};
		byte[] encrypted = AesUtils.encrypt(utf8("비밀"), key, iv, AesUtils.Mode.GCM, aad);

		assertThat(new String(AesUtils.decrypt(encrypted, key, iv, AesUtils.Mode.GCM, aad), StandardCharsets.UTF_8))
			.isEqualTo("비밀");

		byte[] tampered = encrypted.clone();
		tampered[0] ^= 1;
		assertThatThrownBy(() -> AesUtils.decrypt(tampered, key, iv, AesUtils.Mode.GCM, aad))
			.extracting(CryptoUtilsTest::errorCodeOf).isEqualTo(CryptoError.DECRYPT_FAILED);
		assertThatThrownBy(() -> AesUtils.decrypt(encrypted, key, iv, AesUtils.Mode.GCM, new byte[] {2}))
			.extracting(CryptoUtilsTest::errorCodeOf).isEqualTo(CryptoError.DECRYPT_FAILED);
		assertThatThrownBy(() -> AesUtils.decrypt(encrypted, AesUtils.generateKey(32), iv, AesUtils.Mode.GCM, aad))
			.extracting(CryptoUtilsTest::errorCodeOf).isEqualTo(CryptoError.DECRYPT_FAILED);
	}

	@Test
	@DisplayName("IV 를 앞에 붙이는 방식 - GCM/CBC 왕복, 매번 다른 암호문, 벤더 규격 IV 길이 지정")
	void withIv() {
		byte[] key = AesUtils.generateKey(16);
		byte[] first = AesUtils.encryptWithIv(utf8("data"), key, AesUtils.Mode.GCM);
		byte[] second = AesUtils.encryptWithIv(utf8("data"), key, AesUtils.Mode.GCM);

		assertThat(first).isNotEqualTo(second);
		assertThat(AesUtils.decryptWithIv(first, key, AesUtils.Mode.GCM, 12)).isEqualTo(utf8("data"));

		byte[] cbc = AesUtils.encryptWithIv(utf8("legacy"), key, AesUtils.Mode.CBC);
		assertThat(AesUtils.decryptWithIv(cbc, key, AesUtils.Mode.CBC, 16)).isEqualTo(utf8("legacy"));

		byte[] iv16 = new byte[16];
		byte[] encrypted = AesUtils.encrypt(utf8("nice"), key, iv16, AesUtils.Mode.GCM, null);
		byte[] packed = new byte[16 + encrypted.length];
		System.arraycopy(encrypted, 0, packed, 16, encrypted.length);
		assertThat(AesUtils.decryptWithIv(packed, key, AesUtils.Mode.GCM, 16)).isEqualTo(utf8("nice"));
	}

	@Test
	@DisplayName("AES 키 길이 오류 - INVALID_KEY")
	void invalidKey() {
		assertThatThrownBy(() -> AesUtils.generateKey(10))
			.extracting(CryptoUtilsTest::errorCodeOf).isEqualTo(CryptoError.INVALID_KEY);
		assertThatThrownBy(() -> AesUtils.encrypt(utf8("x"), new byte[7], new byte[12], AesUtils.Mode.GCM, null))
			.extracting(CryptoUtilsTest::errorCodeOf).isEqualTo(CryptoError.INVALID_KEY);
	}
}
