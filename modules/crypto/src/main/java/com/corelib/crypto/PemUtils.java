package com.corelib.crypto;

import com.corelib.common.exception.CoreException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.bouncycastle.asn1.pkcs.RSAPublicKey;

/**
 * PEM(-----BEGIN ...-----) 키 읽기·쓰기.
 * <ul>
 *   <li>읽기: PUBLIC KEY(X.509) · RSA PUBLIC KEY(PKCS#1) · PRIVATE KEY(PKCS#8) · RSA PRIVATE KEY(PKCS#1, openssl 기본 출력)</li>
 *   <li>PUBLIC KEY · PRIVATE KEY 는 RSA·EC 둘 다 받는다</li>
 *   <li>쓰기: 공개키는 PUBLIC KEY, 개인키는 PRIVATE KEY(PKCS#8) — 가장 널리 호환되는 형식</li>
 *   <li>암호 걸린 개인키(ENCRYPTED PRIVATE KEY)는 받지 않는다 — 키는 KMS·환경변수로 관리하고 평문 PEM 으로 넘긴다</li>
 * </ul>
 */
public final class PemUtils {

	private static final Pattern PEM = Pattern.compile("-----BEGIN ([A-Z ]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);
	private static final List<String> KEY_ALGORITHMS = List.of("RSA", "EC");
	private static final int LINE_LENGTH = 64;

	private PemUtils() {
	}

	/**
	 * @throws CoreException INVALID_ENCODING(PEM 형식 아님) / INVALID_KEY(지원하지 않는 종류·깨진 키)
	 */
	public static PublicKey readPublicKey(String pem) {
		Block block = parse(pem);
		try {
			return switch (block.type()) {
				case "PUBLIC KEY" -> generate(algorithm -> KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(block.der())));
				case "RSA PUBLIC KEY" -> {
					RSAPublicKey key = RSAPublicKey.getInstance(block.der());
					yield KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
				}
				default -> throw new CoreException(CryptoError.INVALID_KEY, "공개키 PEM 이 아닙니다: " + block.type());
			};
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new CoreException(CryptoError.INVALID_KEY, "공개키를 읽을 수 없습니다.", e);
		}
	}

	/**
	 * @throws CoreException INVALID_ENCODING / INVALID_KEY(암호 걸린 키·지원하지 않는 종류·깨진 키)
	 */
	public static PrivateKey readPrivateKey(String pem) {
		Block block = parse(pem);
		try {
			return switch (block.type()) {
				case "PRIVATE KEY" -> generate(algorithm -> KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(block.der())));
				case "RSA PRIVATE KEY" -> {
					RSAPrivateKey key = RSAPrivateKey.getInstance(block.der());
					yield KeyFactory.getInstance("RSA").generatePrivate(new RSAPrivateCrtKeySpec(key.getModulus(), key.getPublicExponent(),
						key.getPrivateExponent(), key.getPrime1(), key.getPrime2(), key.getExponent1(), key.getExponent2(), key.getCoefficient()));
				}
				case "ENCRYPTED PRIVATE KEY" -> throw new CoreException(CryptoError.INVALID_KEY,
					"암호 걸린 개인키는 지원하지 않습니다. openssl pkcs8 -nocrypt 로 풀어서 안전한 저장소(KMS·환경변수)로 넘기세요.");
				default -> throw new CoreException(CryptoError.INVALID_KEY, "개인키 PEM 이 아닙니다: " + block.type());
			};
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new CoreException(CryptoError.INVALID_KEY, "개인키를 읽을 수 없습니다.", e);
		}
	}

	/** @return -----BEGIN PUBLIC KEY----- (X.509) */
	public static String writePublicKey(PublicKey publicKey) {
		return write("PUBLIC KEY", publicKey.getEncoded());
	}

	/** @return -----BEGIN PRIVATE KEY----- (PKCS#8) */
	public static String writePrivateKey(PrivateKey privateKey) {
		return write("PRIVATE KEY", privateKey.getEncoded());
	}

	private static String write(String type, byte[] der) {
		String body = Base64.getMimeEncoder(LINE_LENGTH, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
		return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
	}

	private static Block parse(String pem) {
		Matcher matcher = pem == null ? null : PEM.matcher(pem);
		if (matcher == null || !matcher.find()) {
			throw new CoreException(CryptoError.INVALID_ENCODING, "PEM 형식이 아닙니다.");
		}
		try {
			return new Block(matcher.group(1), Base64.getMimeDecoder().decode(matcher.group(2)));
		} catch (IllegalArgumentException e) {
			throw new CoreException(CryptoError.INVALID_ENCODING, "PEM 본문이 base64 가 아닙니다.", e);
		}
	}

	/** X.509·PKCS#8 은 알고리즘이 본문 안에 있다 — RSA, EC 순서로 시도 */
	private static <T> T generate(KeyGenerator<T> generator) throws GeneralSecurityException {
		GeneralSecurityException last = null;
		for (String algorithm : KEY_ALGORITHMS) {
			try {
				return generator.generate(algorithm);
			} catch (GeneralSecurityException e) {
				last = e;
			}
		}
		throw last;
	}

	@FunctionalInterface
	private interface KeyGenerator<T> {
		T generate(String algorithm) throws GeneralSecurityException;
	}

	private record Block(String type, byte[] der) {
	}
}
