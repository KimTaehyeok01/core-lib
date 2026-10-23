package com.corelib.auth.nice;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.lang.RandomStringUtils;
import com.corelib.lang.StringUtils;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 벤더 요청 파라미터 결정 — 요청값 → 설정값 순으로 고르고 NICE 제약을 확인한다.
 * <ul>
 *   <li>token 과 url 요청은 서로 관계없는 request_no 를 쓴다 — 호출측이 각각 채번한다</li>
 *   <li>콜백 URL 도메인 대조는 NICE 쪽 등록에 맡기고 스킴·형식·길이만 본다</li>
 * </ul>
 */
final class NiceAuthRequestSupport {

	private static final DateTimeFormatter REQUEST_NO_PREFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
	private static final int REQUEST_NO_RANDOM_LENGTH = 13;
	private static final int CALLBACK_URL_MAX_BYTES = 250;

	private NiceAuthRequestSupport() {
	}

	/** 30자 request_no — 시각(17) + 무작위 영숫자(13). NICE 제약은 20~50자 */
	static String newRequestNo() {
		return LocalDateTime.now().format(REQUEST_NO_PREFIX) + RandomStringUtils.randomAlphanumeric(REQUEST_NO_RANDOM_LENGTH);
	}

	/**
	 * return_url 을 정한다 — 요청값이 없으면 설정값.
	 *
	 * @throws CoreException 형식 위반(400)
	 */
	static String returnUrl(String requested, String configured) {
		return validateUrl(StringUtils.isNotBlank(requested) ? requested : configured, "returnUrl");
	}

	/** close_url 을 정한다 — 선택 항목이라 둘 다 없으면 null 을 돌려 전송에서 뺀다 */
	static String closeUrl(String requested, String configured) {
		String closeUrl = StringUtils.isNotBlank(requested) ? requested : configured;
		return StringUtils.isBlank(closeUrl) ? null : validateUrl(closeUrl, "closeUrl");
	}

	/** 인증 수단 코드 — 요청 → 설정 → 휴대폰(M) 순. 빈 svc_types 는 보내지 않는다 */
	static List<String> svcTypes(List<NiceAuthDto.SvcType> requested, List<NiceAuthDto.SvcType> configured) {
		List<String> codes = toCodes(requested);
		if (codes.isEmpty()) {
			codes = toCodes(configured);
		}
		return codes.isEmpty() ? List.of(NiceAuthDto.SvcType.M.getCode()) : codes;
	}

	private static List<String> toCodes(List<NiceAuthDto.SvcType> svcTypes) {
		return svcTypes == null ? List.of()
			: svcTypes.stream().filter(Objects::nonNull).map(NiceAuthDto.SvcType::getCode).distinct().toList();
	}

	private static String validateUrl(String url, String field) {
		if (StringUtils.isBlank(url)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, field + " 가 없습니다.");
		}
		String trimmed = url.trim();
		URI uri;
		try {
			uri = new URI(trimmed);
		} catch (URISyntaxException e) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, field + " 형식이 올바르지 않습니다.");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!(scheme.equals("http") || scheme.equals("https")) || StringUtils.isBlank(uri.getHost())) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, field + " 은 http 또는 https 절대 URL 이어야 합니다.");
		}
		if (trimmed.getBytes(StandardCharsets.UTF_8).length > CALLBACK_URL_MAX_BYTES) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, field + " 은 250 byte 이하여야 합니다.");
		}
		return trimmed;
	}
}
