package com.corelib.example.crypto.service;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.crypto.AesUtils;
import com.corelib.crypto.Base64Utils;
import com.corelib.crypto.HmacUtils;
import com.corelib.crypto.KdfUtils;
import com.corelib.example.crypto.dto.CustomerExampleDto;
import com.corelib.lang.MaskingUtils;
import com.corelib.lang.StringUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 개인정보 암호화 고객 서비스.
 * <ul>
 *   <li>이름·이메일·휴대폰 — {@link AesUtils#encryptWithIv} AES-256-GCM, 매번 새 IV</li>
 *   <li>이메일 중복확인·로그인 조회 — 암호문은 매번 달라 검색이 안 되므로 {@link HmacUtils} 검색 인덱스(blind index)</li>
 *   <li>비밀번호 — {@link KdfUtils#pbkdf2Sha256} + 고객별 salt, 비교는 {@link HmacUtils#isEqual}(상수 시간)</li>
 *   <li>화면 응답 — 복호화 후 {@link MaskingUtils}</li>
 * </ul>
 */
@Slf4j
@Service
public class CustomerExampleService {

	/** OWASP 권고(PBKDF2-HmacSHA256) */
	private static final int PASSWORD_ITERATIONS = 600_000;
	private static final int PASSWORD_HASH_BITS = 256;
	private static final int SALT_BYTES = 16;
	private static final int GCM_IV_BYTES = AesUtils.Mode.GCM.getIvLength();

	private static final ErrorCode EMAIL_DUPLICATED = ErrorCode.of("CUSTOMER_EMAIL_DUPLICATED", "이미 가입된 이메일입니다.", 409);
	private static final ErrorCode LOGIN_FAILED = ErrorCode.of("CUSTOMER_LOGIN_FAILED", "이메일 또는 비밀번호가 올바르지 않습니다.", 401);
	private static final ErrorCode LOGIN_LOCKED = ErrorCode.of("CUSTOMER_LOGIN_LOCKED", "로그인 시도가 너무 많습니다. 5분 후 다시 시도해 주세요.", 429);
	private static final int MAX_LOGIN_FAILS = 5;
	private static final Duration LOGIN_LOCK_TIME = Duration.ofMinutes(5);

	/**
	 * 실서비스는 고객 테이블 (email_index unique) — 예제라 메모리에 둔다.
	 * 변경은 computeIfPresent 로 최신 값에 원자 적용 — 실서비스는 DB 조건부 UPDATE(바꿀 컬럼만 SET) 로 같은 원자성 확보
	 */
	private final Map<String, CustomerExampleDto.Customer> customersByEmailIndex = new ConcurrentHashMap<>();
	/** 키: 이메일 인덱스. 증가·잠금은 compute 로 원자 처리 — 실서비스는 DB 조건부 UPDATE·Redis INCR 로 같은 원자성 확보 */
	private final Map<String, CustomerExampleDto.LoginFailure> loginFailuresByEmailIndex = new ConcurrentHashMap<>();
	private final AtomicLong sequence = new AtomicLong();
	/** 없는 이메일 로그인에도 같은 PBKDF2 를 계산할 salt — 응답 시간으로 가입 여부를 알 수 없게 */
	private final byte[] dummySalt = AesUtils.generateKey(SALT_BYTES);

	private final byte[] aesKey;
	private final byte[] indexKey;
	private final boolean isDebugEndpointsEnabled;

	/**
	 * 키는 설정(환경변수·KMS)으로만 받는다 — 코드·yml 에 리터럴 금지.
	 * 예제라 비어 있으면 기동할 때 임시 키를 만든다 (재시작하면 이전 암호문은 못 푼다).
	 */
	public CustomerExampleService(@Value("${example.crypto.aes-key:}") String aesKeyBase64,
								  @Value("${example.crypto.index-key:}") String indexKeyBase64,
								  @Value("${example.crypto.debug-endpoints:false}") boolean isDebugEndpointsEnabled) {
		this.aesKey = loadKey(aesKeyBase64, "example.crypto.aes-key");
		this.indexKey = loadKey(indexKeyBase64, "example.crypto.index-key");
		this.isDebugEndpointsEnabled = isDebugEndpointsEnabled;
	}

	public CustomerExampleDto.CustomerResponse join(CustomerExampleDto.JoinRequest request) {
		ValidateUtils.validate(request);
		String emailIndex = emailIndex(request.getEmail());
		byte[] salt = AesUtils.generateKey(SALT_BYTES);    // SecureRandom 난수 — salt 로 쓴다

		CustomerExampleDto.Customer customer = CustomerExampleDto.Customer.builder()
																		  .customerId(sequence.incrementAndGet())
																		  .emailIndex(emailIndex)
																		  .encryptedName(encrypt(request.getName().trim()))
																		  .encryptedEmail(encrypt(request.getEmail().trim()))
																		  .encryptedMobileNo(encrypt(digitsOf(request.getMobileNo())))
																		  .passwordSalt(Base64Utils.encodeUrl(salt))
																		  .passwordHash(hashPassword(request.getPassword(), salt))
																		  .joinedAt(LocalDateTime.now())
																		  .build();
		if (customersByEmailIndex.putIfAbsent(emailIndex, customer) != null) {
			throw new CoreException(EMAIL_DUPLICATED);
		}
		log.info("고객 가입. customerId={}", customer.getCustomerId());
		return toResponse(customer);
	}

	/** 로그인 — 이메일이 없을 때와 비밀번호가 틀릴 때 같은 응답·같은 계산량 (가입 여부 노출 방지). 5번 틀리면 5분 잠금(429) */
	public CustomerExampleDto.CustomerResponse login(CustomerExampleDto.LoginRequest request) {
		ValidateUtils.validate(request);
		return toResponse(authenticate(request.getEmail(), request.getPassword()));
	}

	/** 휴대폰번호 변경 — 본인 확인 후 새 IV 로 다시 암호화 */
	public CustomerExampleDto.CustomerResponse changeMobile(CustomerExampleDto.ChangeMobileRequest request) {
		ValidateUtils.validate(request);
		CustomerExampleDto.Customer customer = authenticate(request.getEmail(), request.getPassword());
		String encryptedMobileNo = encrypt(digitsOf(request.getMobileNo()));
		// 처음 읽은 스냅샷으로 덮지 않고 최신 값의 휴대폰만 바꾼다 — 그 사이 다른 변경이 있어도 유실되지 않게
		CustomerExampleDto.Customer updated = customersByEmailIndex.computeIfPresent(customer.getEmailIndex(),
			(emailIndex, current) -> current.toBuilder().encryptedMobileNo(encryptedMobileNo).build());
		return toResponse(updated);
	}

	public List<CustomerExampleDto.CustomerResponse> list() {
		return customersByEmailIndex.values().stream()
									.sorted(Comparator.comparing(CustomerExampleDto.Customer::getCustomerId))
									.map(this::toResponse)
									.toList();
	}

	/** DB 에 실제로 저장되는 모습 — 원문이 없다는 것을 확인하는 용도. example.crypto.debug-endpoints=true 일 때만, 아니면 없는 경로와 같은 404 */
	public List<CustomerExampleDto.StoredRow> storedRows() {
		if (!isDebugEndpointsEnabled) {
			throw new CoreException(CommonErrorCode.NOT_FOUND);
		}
		return customersByEmailIndex.values().stream()
									.sorted(Comparator.comparing(CustomerExampleDto.Customer::getCustomerId))
									.map(customer -> CustomerExampleDto.StoredRow.builder()
																				 .customerId(customer.getCustomerId())
																				 .emailIndex(customer.getEmailIndex())
																				 .encryptedEmail(customer.getEncryptedEmail())
																				 .encryptedMobileNo(customer.getEncryptedMobileNo())
																				 .build())
									.toList();
	}

	private CustomerExampleDto.Customer authenticate(String email, String password) {
		String emailIndex = emailIndex(email);
		countLoginAttempt(emailIndex);
		CustomerExampleDto.Customer customer = customersByEmailIndex.get(emailIndex);
		byte[] salt = customer != null ? Base64Utils.decodeUrl(customer.getPasswordSalt()) : dummySalt;
		String expected = hashPassword(password, salt);
		if (customer == null) {
			throw new CoreException(LOGIN_FAILED);
		}
		if (!HmacUtils.isEqual(customer.getPasswordHash(), expected)) {
			throw new CoreException(LOGIN_FAILED).addErrorLogData("customerId", customer.getCustomerId());
		}
		loginFailuresByEmailIndex.remove(emailIndex);
		return customer;
	}

	/** 비밀번호를 계산하기 전에 시도를 먼저 센다 — 동시에 몰려와도 잠금 전까지 최대 5번만 계산. 잠금 중이면 계산 없이 429 */
	private void countLoginAttempt(String emailIndex) {
		LocalDateTime now = LocalDateTime.now();
		loginFailuresByEmailIndex.compute(emailIndex, (k, failure) -> {
			if (failure != null && failure.getLockExpiresAt() != null && now.isBefore(failure.getLockExpiresAt())) {
				throw new CoreException(LOGIN_LOCKED);    // 예외면 기존 값 유지
			}
			int failCount = failure == null || failure.getLockExpiresAt() != null ? 1 : failure.getFailCount() + 1;    // 잠금이 풀렸으면 새로 센다
			return CustomerExampleDto.LoginFailure.builder()
												  .failCount(failCount)
												  .lockExpiresAt(failCount >= MAX_LOGIN_FAILS ? now.plus(LOGIN_LOCK_TIME) : null)
												  .build();
		});
	}

	private CustomerExampleDto.CustomerResponse toResponse(CustomerExampleDto.Customer customer) {
		return CustomerExampleDto.CustomerResponse.builder()
												  .customerId(customer.getCustomerId())
												  .maskedName(MaskingUtils.name(decrypt(customer.getEncryptedName())))
												  .maskedEmail(MaskingUtils.email(decrypt(customer.getEncryptedEmail())))
												  .maskedMobileNo(MaskingUtils.mobile(decrypt(customer.getEncryptedMobileNo())))
												  .joinedAt(customer.getJoinedAt())
												  .build();
	}

	/** 대소문자·앞뒤 공백이 달라도 같은 이메일이면 같은 인덱스 */
	private String emailIndex(String email) {
		String normalized = StringUtils.defaultIfBlank(StringUtils.trimToNull(email), "").toLowerCase(Locale.ROOT);
		return Base64Utils.encodeUrl(HmacUtils.hmacSha256(indexKey, normalized.getBytes(StandardCharsets.UTF_8)));
	}

	private static String hashPassword(String password, byte[] salt) {
		return Base64Utils.encodeUrl(KdfUtils.pbkdf2Sha256(password.toCharArray(), salt, PASSWORD_ITERATIONS, PASSWORD_HASH_BITS));
	}

	private String encrypt(String plain) {
		return Base64Utils.encodeUrl(AesUtils.encryptWithIv(plain.getBytes(StandardCharsets.UTF_8), aesKey, AesUtils.Mode.GCM));
	}

	/** 키가 다르거나 암호문이 바뀌었으면 CRYPTO-DECRYPT */
	private String decrypt(String sealed) {
		return new String(AesUtils.decryptWithIv(Base64Utils.decodeUrl(sealed), aesKey, AesUtils.Mode.GCM, GCM_IV_BYTES), StandardCharsets.UTF_8);
	}

	private static String digitsOf(String mobileNo) {
		return mobileNo.replaceAll("[^0-9]", "");
	}

	private static byte[] loadKey(String base64, String name) {
		if (StringUtils.isBlank(base64)) {
			log.warn("{} 미설정 - 임시 키를 생성합니다. 재시작하면 이전 암호문은 복호화할 수 없습니다.", name);
			return AesUtils.generateKey(32);
		}
		byte[] key = Base64Utils.decode(base64.trim());
		if (key.length != 32) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, name + " 는 base64 로 인코딩한 32byte 여야 합니다.");
		}
		return key;
	}
}
