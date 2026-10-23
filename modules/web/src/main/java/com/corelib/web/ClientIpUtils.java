package com.corelib.web;

import com.corelib.lang.StringUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 실제 클라이언트 IP 확인.
 * <ul>
 *   <li>직접 연결한 주소(remoteAddr)가 신뢰 프록시일 때만 X-Forwarded-For 를 본다 — 아니면 헤더는 위조일 수 있어 무시한다</li>
 *   <li>X-Forwarded-For 는 오른쪽(가까운 프록시)부터 읽으며 신뢰 프록시를 건너뛴다 — 맨 왼쪽 값은 클라이언트가 마음대로 넣을 수 있다</li>
 *   <li>IP 형식이 아닌 값은 버린다 — 호스트 이름을 DNS 조회하지 않는다</li>
 * </ul>
 */
public final class ClientIpUtils {

	private static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");
	private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

	private static volatile List<IpRange> trustedProxies = ranges(new WebProperty.ClientIp().getTrustedProxies());
	private static volatile List<String> headers = new WebProperty.ClientIp().getHeaders();

	private ClientIpUtils() {
	}

	/** 기동 시 {@link WebInitialize} 가 1회 호출한다 — 호출 전에는 기본값(루프백·사설망 신뢰)으로 동작 */
	static void initialize(WebProperty.ClientIp property) {
		trustedProxies = ranges(property.getTrustedProxies());
		headers = List.copyOf(property.getHeaders());
	}

	/** @return 클라이언트 IP — 끝내 알 수 없으면 remoteAddr */
	public static String resolve(HttpServletRequest request) {
		String remoteAddr = normalize(request.getRemoteAddr());
		if (remoteAddr == null || !isTrusted(remoteAddr)) {
			return request.getRemoteAddr();
		}
		for (String header : headers) {
			String value = request.getHeader(header);
			if (StringUtils.isBlank(value)) {
				continue;
			}
			String[] hops = value.split(",");
			String leftmost = null;
			for (int i = hops.length - 1; i >= 0; i--) {
				String hop = normalize(hops[i]);
				if (hop == null) {
					break;    // 형식이 깨진 값부터 왼쪽은 믿을 수 없다
				}
				if (!isTrusted(hop)) {
					return hop;
				}
				leftmost = hop;
			}
			if (leftmost != null) {
				return leftmost;    // 전부 신뢰 프록시 — 내부망 요청
			}
		}
		return remoteAddr;
	}

	/** @return 신뢰 프록시 대역이면 true */
	static boolean isTrusted(String ip) {
		byte[] address = toBytes(ip);
		return address != null && trustedProxies.stream().anyMatch(range -> range.contains(address));
	}

	/** 공백·대괄호·포트를 떼고 IP 형식만 남긴다 — 아니면 null */
	static String normalize(String value) {
		String text = StringUtils.trimToNull(value);
		if (text == null) {
			return null;
		}
		if (text.startsWith("[") && text.contains("]")) {
			text = text.substring(1, text.indexOf(']'));    // [::1]:8080
		} else if (text.indexOf(':') > 0 && text.indexOf(':') == text.lastIndexOf(':') && text.contains(".")) {
			text = text.substring(0, text.indexOf(':'));    // 1.2.3.4:5678
		}
		if (IPV4.matcher(text).matches() || (text.contains(":") && IPV6.matcher(text).matches() && toBytes(text) != null)) {
			return text;
		}
		return null;
	}

	/** IP 문자열(형식 검사를 통과한 것만) → byte — 형식 검사 뒤라 DNS 조회가 일어나지 않는다 */
	private static byte[] toBytes(String ip) {
		if (!IPV4.matcher(ip).matches() && !(ip.contains(":") && IPV6.matcher(ip).matches())) {
			return null;
		}
		try {
			return InetAddress.getByName(ip).getAddress();
		} catch (UnknownHostException e) {
			return null;
		}
	}

	private static List<IpRange> ranges(List<String> cidrs) {
		List<IpRange> result = new ArrayList<>();
		for (String cidr : cidrs) {
			result.add(IpRange.parse(cidr));
		}
		return List.copyOf(result);
	}

	/** CIDR 대역 (예: 10.0.0.0/8, ::1/128) */
	private record IpRange(byte[] network, int prefix) {

		static IpRange parse(String cidr) {
			String[] parts = cidr.trim().split("/");
			byte[] network = toBytes(parts[0]);
			if (network == null) {
				throw new IllegalArgumentException("core.web.client-ip.trusted-proxies 형식 오류: " + cidr);
			}
			int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : network.length * 8;
			if (prefix < 0 || prefix > network.length * 8) {
				throw new IllegalArgumentException("core.web.client-ip.trusted-proxies 대역 길이 오류: " + cidr);
			}
			return new IpRange(network, prefix);
		}

		boolean contains(byte[] address) {
			if (address.length != network.length) {
				return false;
			}
			int fullBytes = prefix / 8;
			for (int i = 0; i < fullBytes; i++) {
				if (address[i] != network[i]) {
					return false;
				}
			}
			int remainBits = prefix % 8;
			if (remainBits == 0) {
				return true;
			}
			int mask = 0xFF << (8 - remainBits);
			return (address[fullBytes] & mask) == (network[fullBytes] & mask);
		}
	}
}
