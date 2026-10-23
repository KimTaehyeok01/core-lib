package com.corelib.lang;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaskingUtilsTest {

	@Test
	@DisplayName("이름 - 첫·끝 글자만 남김, 두 글자는 첫 글자만, 한 글자는 전부")
	void name() {
		assertThat(MaskingUtils.name("홍길동")).isEqualTo("홍*동");
		assertThat(MaskingUtils.name("남궁민수")).isEqualTo("남**수");
		assertThat(MaskingUtils.name("이산")).isEqualTo("이*");
		assertThat(MaskingUtils.name("김")).isEqualTo("*");
		assertThat(MaskingUtils.name(" John Smith ")).isEqualTo("J********h");
		assertThat(MaskingUtils.name(" ")).isNull();
	}

	@Test
	@DisplayName("휴대폰 - 가운데 가림, 하이픈 유무 무관, 형식 오류는 전부 가림")
	void mobile() {
		assertThat(MaskingUtils.mobile("01012345678")).isEqualTo("010-****-5678");
		assertThat(MaskingUtils.mobile("010-1234-5678")).isEqualTo("010-****-5678");
		assertThat(MaskingUtils.mobile("0111234567")).isEqualTo("011-***-4567");
		assertThat(MaskingUtils.mobile("1234")).isEqualTo("****");
		assertThat(MaskingUtils.mobile(null)).isNull();
	}

	@Test
	@DisplayName("이메일 - 아이디 앞 2글자만 남김, 짧은 아이디·형식 오류는 전부 가림")
	void email() {
		assertThat(MaskingUtils.email("user@example.com")).isEqualTo("us**@example.com");
		assertThat(MaskingUtils.email("ab@b.com")).isEqualTo("**@b.com");
		assertThat(MaskingUtils.email("no-at-sign")).isEqualTo("****");
		assertThat(MaskingUtils.email("")).isNull();
	}
}
