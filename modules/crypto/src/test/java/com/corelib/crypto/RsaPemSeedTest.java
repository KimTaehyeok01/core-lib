package com.corelib.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.exception.CoreException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RsaPemSeedTest {

	private static KeyPair keyPair;

	@BeforeAll
	static void createKey() {
		keyPair = RsaUtils.generateKeyPair(2048);
	}

	private static byte[] utf8(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("Hex — 소문자 출력, 대문자 입력 허용, 형식 오류는 CRYPTO-ENCODING")
	void hex() {
		assertThat(HexUtils.encode(new byte[] {0x00, 0x0f, (byte) 0xff})).isEqualTo("000fff");
		assertThat(HexUtils.decode("000FFF")).containsExactly(0x00, 0x0f, 0xff);
		assertThatThrownBy(() -> HexUtils.decode("abc")).isInstanceOf(CoreException.class)
														.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(CryptoError.INVALID_ENCODING);
	}

	@Test
	@DisplayName("RSA OAEP-SHA256 — 왕복, 매번 다른 암호문, 다른 키로는 복호화 실패")
	void rsaOaep() {
		byte[] first = RsaUtils.encrypt(utf8("카드번호"), keyPair.getPublic());
		byte[] second = RsaUtils.encrypt(utf8("카드번호"), keyPair.getPublic());

		assertThat(first).isNotEqualTo(second);
		assertThat(new String(RsaUtils.decrypt(first, keyPair.getPrivate()), StandardCharsets.UTF_8)).isEqualTo("카드번호");
		assertThatThrownBy(() -> RsaUtils.decrypt(first, RsaUtils.generateKeyPair(2048).getPrivate()))
			.isInstanceOf(CoreException.class)
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(CryptoError.DECRYPT_FAILED);
	}

	@Test
	@DisplayName("RSA PKCS#1 v1.5 왕복(벤더 호환), 키 길이보다 긴 데이터는 ENCRYPT_FAILED, 2048bit 미만 키 거부")
	void rsaLimits() {
		byte[] sealed = RsaUtils.encrypt(utf8("legacy"), keyPair.getPublic(), RsaUtils.Padding.PKCS1_V1_5);
		assertThat(RsaUtils.decrypt(sealed, keyPair.getPrivate(), RsaUtils.Padding.PKCS1_V1_5)).isEqualTo(utf8("legacy"));

		assertThatThrownBy(() -> RsaUtils.encrypt(new byte[300], keyPair.getPublic()))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(CryptoError.ENCRYPT_FAILED);
		assertThatThrownBy(() -> RsaUtils.generateKeyPair(1024))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(CryptoError.INVALID_KEY);
	}

	@Test
	@DisplayName("RSA 서명 — 맞으면 true, 데이터·서명이 한 글자라도 바뀌면 false")
	void rsaSignature() {
		byte[] signature = RsaUtils.sign(utf8("amount=10000"), keyPair.getPrivate());

		assertThat(RsaUtils.verify(utf8("amount=10000"), signature, keyPair.getPublic())).isTrue();
		assertThat(RsaUtils.verify(utf8("amount=99999"), signature, keyPair.getPublic())).isFalse();
		byte[] tampered = Arrays.copyOf(signature, signature.length);
		tampered[0] ^= 1;
		assertThat(RsaUtils.verify(utf8("amount=10000"), tampered, keyPair.getPublic())).isFalse();
		assertThat(RsaUtils.verify(utf8("amount=10000"), new byte[3], keyPair.getPublic())).isFalse();
	}

	@Test
	@DisplayName("PEM — 쓰고 다시 읽으면 같은 키, PKCS#1(openssl 기본)·EC 키도 읽음")
	void pem() throws Exception {
		String publicPem = PemUtils.writePublicKey(keyPair.getPublic());
		String privatePem = PemUtils.writePrivateKey(keyPair.getPrivate());
		assertThat(publicPem).startsWith("-----BEGIN PUBLIC KEY-----\n");
		assertThat(PemUtils.readPublicKey(publicPem)).isEqualTo(keyPair.getPublic());
		assertThat(PemUtils.readPrivateKey(privatePem)).isEqualTo(keyPair.getPrivate());

		// openssl genrsa 가 만드는 PKCS#1 형식
		byte[] pkcs1 = PrivateKeyInfo.getInstance(keyPair.getPrivate().getEncoded()).parsePrivateKey().toASN1Primitive().getEncoded();
		String pkcs1Pem = "-----BEGIN RSA PRIVATE KEY-----\r\n" + Base64.getMimeEncoder().encodeToString(pkcs1) + "\r\n-----END RSA PRIVATE KEY-----";
		PrivateKey fromPkcs1 = PemUtils.readPrivateKey("앞 설명\n" + pkcs1Pem + "\n뒤 설명");
		byte[] signature = RsaUtils.sign(utf8("x"), fromPkcs1);
		assertThat(RsaUtils.verify(utf8("x"), signature, keyPair.getPublic())).isTrue();

		KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
		ec.initialize(new ECGenParameterSpec("secp256r1"));
		PublicKey ecKey = ec.generateKeyPair().getPublic();
		assertThat(PemUtils.readPublicKey(PemUtils.writePublicKey(ecKey))).isEqualTo(ecKey);
	}

	@Test
	@DisplayName("PEM — 형식 아님은 CRYPTO-ENCODING, 암호 걸린 키·종류 불일치는 CRYPTO-KEY")
	void pemErrors() {
		assertThatThrownBy(() -> PemUtils.readPublicKey("not pem"))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(CryptoError.INVALID_ENCODING);
		assertThatThrownBy(() -> PemUtils.readPrivateKey("-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----"))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(CryptoError.INVALID_KEY);
		assertThatThrownBy(() -> PemUtils.readPrivateKey(PemUtils.writePublicKey(keyPair.getPublic())))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(CryptoError.INVALID_KEY);
	}

	@Test
	@DisplayName("SEED — RFC 4269 테스트 벡터 (키 0, 평문 00..0F)")
	void seedVector() {
		byte[] key = new byte[16];
		byte[] plain = HexUtils.decode("000102030405060708090a0b0c0d0e0f");

		byte[] cipherText = SeedUtils.encryptEcb(plain, key);

		assertThat(HexUtils.encode(Arrays.copyOf(cipherText, 16))).isEqualTo("5ebac6e0054e166819aff1cc6d346cdb");
		assertThat(SeedUtils.decryptEcb(cipherText, key)).isEqualTo(plain);
	}

	@Test
	@DisplayName("SEED-CBC — 매번 새 IV 로 왕복, 다른 키는 DECRYPT_FAILED, 키 길이 오류는 INVALID_KEY")
	void seedCbc() {
		byte[] key = SeedUtils.generateKey();
		byte[] first = SeedUtils.encryptWithIv(utf8("주민번호 뒷자리"), key);
		byte[] second = SeedUtils.encryptWithIv(utf8("주민번호 뒷자리"), key);

		assertThat(first).isNotEqualTo(second);
		assertThat(new String(SeedUtils.decryptWithIv(first, key), StandardCharsets.UTF_8)).isEqualTo("주민번호 뒷자리");
		assertThatThrownBy(() -> SeedUtils.decryptWithIv(first, SeedUtils.generateKey()))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(CryptoError.DECRYPT_FAILED);
		assertThatThrownBy(() -> SeedUtils.encryptEcb(utf8("x"), new byte[10]))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(CryptoError.INVALID_KEY);
	}
}
