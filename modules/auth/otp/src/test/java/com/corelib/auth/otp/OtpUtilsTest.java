package com.corelib.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.exception.CoreException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OtpUtilsTest {

	@AfterEach
	void reset() {
		OtpUtils.initialize(new OtpProperty());
	}

	private static OtpProperty eightDigits(OtpAlgorithm algorithm) {
		OtpProperty property = new OtpProperty();
		property.setAlgorithm(algorithm);
		property.setDigits(8);
		return property;
	}

	/** RFC 6238 부록 B 의 테스트 비밀값 (SHA1 20byte · SHA256 32byte · SHA512 64byte) */
	private static byte[] rfcSeed(OtpAlgorithm algorithm) {
		String base = "1234567890";
		int length = switch (algorithm) {
			case SHA1 -> 20;
			case SHA256 -> 32;
			case SHA512 -> 64;
		};
		return base.repeat(7).substring(0, length).getBytes(StandardCharsets.US_ASCII);
	}

	@ParameterizedTest(name = "{0} T={1} → {2}")
	@CsvSource({
		"SHA1, 59, 94287082", "SHA256, 59, 46119246", "SHA512, 59, 90693936",
		"SHA1, 1111111109, 07081804", "SHA256, 1111111109, 68084774", "SHA512, 1111111109, 25091201",
		"SHA1, 2000000000, 69279037", "SHA256, 2000000000, 90698825", "SHA512, 2000000000, 38618901"
	})
	@DisplayName("RFC 6238 부록 B 테스트 벡터")
	void rfcVectors(OtpAlgorithm algorithm, long epochSecond, String expected) {
		OtpProperty property = eightDigits(algorithm);
		String secret = OtpUtils.base32Encode(rfcSeed(algorithm));

		OtpUtils.initialize(property);
		assertThat(OtpUtils.generateCode(secret, Instant.ofEpochSecond(epochSecond))).isEqualTo(expected);
	}

	@Test
	@DisplayName("등록 — 32자 Base32 비밀키, otpauth URI(issuer·계정 인코딩, Google Authenticator 기본값)")
	void register() {
		OtpDto.Registration registration = OtpUtils.register("hong@example.com");

		assertThat(registration.getSecret()).matches("[A-Z2-7]{32}");
		assertThat(registration.getOtpauthUri()).isEqualTo("otpauth://totp/core-lib:hong%40example.com?secret=" + registration.getSecret()
			+ "&issuer=core-lib&algorithm=SHA1&digits=6&period=30");
		assertThat(OtpUtils.base32Decode(registration.getSecret().toLowerCase())).hasSize(20);
	}

	@Test
	@DisplayName("검증 — 지금 코드·30초 전 코드 통과, 2단계 전은 실패, 형식 오류 실패")
	void verify() {
		String secret = OtpUtils.generateSecret();
		Instant now = Instant.now();

		assertThat(OtpUtils.verify(secret, OtpUtils.generateCode(secret, now)).isValid()).isTrue();
		assertThat(OtpUtils.verify(secret, OtpUtils.generateCode(secret, now.minusSeconds(30))).isValid()).isTrue();
		assertThat(OtpUtils.verify(secret, OtpUtils.generateCode(secret, now.minusSeconds(90))).isValid()).isFalse();
		assertThat(OtpUtils.verify(secret, "12345").isValid()).isFalse();
		assertThat(OtpUtils.verify(secret, "abcdef").isValid()).isFalse();
		assertThat(OtpUtils.verify(secret, null).isValid()).isFalse();
	}

	@Test
	@DisplayName("재사용 방지 — 성공한 timeStep 을 넘기면 같은 코드는 다시 통과하지 못한다")
	void replay() {
		String secret = OtpUtils.generateSecret();
		String code = OtpUtils.generateCode(secret, Instant.now());

		OtpDto.VerifyResult first = OtpUtils.verify(secret, code, -1);
		assertThat(first.isValid()).isTrue();
		assertThat(OtpUtils.verify(secret, code, first.getTimeStep()).isValid()).isFalse();
	}

	@Test
	@DisplayName("잘못된 비밀키·설정 — Base32 아닌 문자 C400, digits 범위 밖은 기동 오류")
	void invalidInput() {
		assertThatThrownBy(() -> OtpUtils.verify("not-base32!", "123456")).isInstanceOf(CoreException.class);
		OtpProperty property = new OtpProperty();
		property.setDigits(10);
		assertThatThrownBy(() -> OtpUtils.initialize(property)).isInstanceOf(CoreException.class);
	}

	@Test
	@DisplayName("기본 로케일 무관 — 태국 숫자 로케일에서도 ASCII 코드·다른 숫자 체계 입력 거부, 터키어 로케일에서도 소문자 비밀키 해석")
	void localeIndependent() {
		Locale original = Locale.getDefault();
		Locale originalFormat = Locale.getDefault(Locale.Category.FORMAT);
		Locale originalDisplay = Locale.getDefault(Locale.Category.DISPLAY);
		try {
			Locale.setDefault(Locale.forLanguageTag("th-TH-u-nu-thai"));
			String rfcSecret = OtpUtils.base32Encode(rfcSeed(OtpAlgorithm.SHA1));
			OtpUtils.initialize(eightDigits(OtpAlgorithm.SHA1));
			assertThat(OtpUtils.generateCode(rfcSecret, Instant.ofEpochSecond(59))).isEqualTo("94287082");

			OtpUtils.initialize(new OtpProperty());
			String secret = OtpUtils.generateSecret();
			String code = OtpUtils.generateCode(secret, Instant.now());
			assertThat(code).matches("[0-9]{6}");
			assertThat(OtpUtils.verify(secret, code).isValid()).isTrue();
			// ASCII 로 바꾸면 둘 다 '?' 가 되는 숫자들 — 코드가 로케일 숫자로 찍히면 아무 값이나 통과하던 경로
			assertThat(OtpUtils.verify(secret, "๑๒๓๔๕๖").isValid()).isFalse();
			assertThat(OtpUtils.verify(secret, "١٢٣٤٥٦").isValid()).isFalse();

			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			assertThat(OtpUtils.base32Decode("iiiiiiii")).isEqualTo(OtpUtils.base32Decode("IIIIIIII"));
		} finally {
			Locale.setDefault(original);
			Locale.setDefault(Locale.Category.FORMAT, originalFormat);
			Locale.setDefault(Locale.Category.DISPLAY, originalDisplay);
		}
	}
}
