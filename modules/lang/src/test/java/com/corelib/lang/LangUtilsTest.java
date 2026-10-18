// StringUtils·CollectionUtils·RandomStringUtils 동작 검증
package com.corelib.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LangUtilsTest {

	@Test
	@DisplayName("StringUtils - 공백 판정·기본값·trimToNull·truncate")
	void stringUtils() {
		assertThat(StringUtils.isBlank(null)).isTrue();
		assertThat(StringUtils.isBlank(" \t")).isTrue();
		assertThat(StringUtils.isNotBlank("a")).isTrue();
		assertThat(StringUtils.defaultIfBlank(" ", "기본")).isEqualTo("기본");
		assertThat(StringUtils.trimToNull("  a ")).isEqualTo("a");
		assertThat(StringUtils.trimToNull("   ")).isNull();
		assertThat(StringUtils.truncate("abcdef", 3)).isEqualTo("abc");
		assertThat(StringUtils.truncate(null, 3)).isNull();
		assertThatThrownBy(() -> StringUtils.truncate("a", -1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("CollectionUtils - null/빈 컬렉션·맵 판정, emptyIfNull")
	void collectionUtils() {
		assertThat(CollectionUtils.isEmpty((List<?>) null)).isTrue();
		assertThat(CollectionUtils.isEmpty(List.of())).isTrue();
		assertThat(CollectionUtils.isNotEmpty(List.of(1))).isTrue();
		assertThat(CollectionUtils.isEmpty((Map<?, ?>) null)).isTrue();
		assertThat(CollectionUtils.isNotEmpty(Map.of("a", 1))).isTrue();
		assertThat(CollectionUtils.emptyIfNull(null)).isEmpty();
	}

	@Test
	@DisplayName("RandomStringUtils - 길이·문자 집합 준수, 매번 다른 값")
	void randomStringUtils() {
		assertThat(RandomStringUtils.randomNumeric(6)).hasSize(6).matches("[0-9]{6}");
		assertThat(RandomStringUtils.randomAlphabetic(10)).matches("[A-Za-z]{10}");
		assertThat(RandomStringUtils.randomAlphanumeric(0)).isEmpty();

		Set<String> values = new HashSet<>();
		for (int i = 0; i < 100; i++) {
			values.add(RandomStringUtils.randomAlphanumeric(16));
		}
		assertThat(values).hasSize(100);
		assertThatThrownBy(() -> RandomStringUtils.randomNumeric(-1)).isInstanceOf(IllegalArgumentException.class);
	}
}
