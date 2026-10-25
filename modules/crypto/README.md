# crypto

암호화 공통 유틸 — AES(GCM/CBC)·RSA·PEM·SEED·HMAC·SHA-256·Hex·Base64(url)·PBKDF2.
JDK 표준 API 를 쓰고, SEED·PKCS#1 키 해석에만 BouncyCastle(`bcprov-jdk18on` 1.86)을 쓴다.

## 의존성
```groovy
implementation 'com.corelib:crypto:0.2.0'   // 저장소 설정은 루트 README "설치"
```
설정 없음 — 키는 호출할 때 넘긴다 (환경변수·KMS 에서 읽어서).

## 사용법
```java
// AES-256-GCM — 개인정보 컬럼 암호화 (매번 새 IV, 위변조 검출)
byte[] key = AesUtils.generateKey(32);
byte[] sealed = AesUtils.encryptWithIv(plain, key, AesUtils.Mode.GCM);          // IV || 암호문
byte[] plain  = AesUtils.decryptWithIv(sealed, key, AesUtils.Mode.GCM, 12);
String column = Base64Utils.encodeUrl(sealed);                                  // DB 저장

// 검색용 인덱스(blind index)·서명 — 비교는 항상 상수 시간
byte[] mac = HmacUtils.hmacSha256(indexKey, email.getBytes(UTF_8));
HmacUtils.isEqual(expected, actual);
String hash = HashUtils.sha256Hex(text);

// 비밀번호 — PBKDF2 + 사용자별 salt
byte[] hash = KdfUtils.pbkdf2Sha256(password.toCharArray(), salt, 600_000, 256);

// RSA — 벤더 공개키로 암호화, 서명 검증
PublicKey publicKey = PemUtils.readPublicKey(pem);                              // PUBLIC KEY · RSA PUBLIC KEY
byte[] encrypted = RsaUtils.encrypt(data, publicKey);                           // OAEP-SHA256
byte[] legacy = RsaUtils.encrypt(data, publicKey, RsaUtils.Padding.PKCS1_V1_5); // 벤더 규격이 PKCS#1 일 때
boolean isValid = RsaUtils.verify(data, signature, publicKey);                  // SHA256withRSA

// SEED — 국내 기관·금융 연동 규격
byte[] seedSealed = SeedUtils.encryptWithIv(plain, seedKey);                    // SEED-CBC, IV || 암호문
```

## API 요약
| 클래스 | 메서드 |
|---|---|
| `AesUtils` | `generateKey(16/24/32)` · `generateIv` · `encrypt` · `decrypt` (IV·AAD 직접) · `encryptWithIv` · `decryptWithIv` — `Mode.GCM`(IV 12, 복호화만 벤더 호환 16) · `Mode.CBC`(IV 16) |
| `RsaUtils` | `generateKeyPair(2048+)` · `encrypt` · `decrypt` (`Padding.OAEP_SHA256` 기본 · `PKCS1_V1_5`) · `sign` · `verify` |
| `PemUtils` | `readPublicKey` (X.509·PKCS#1, RSA·EC) · `readPrivateKey` (PKCS#8·PKCS#1, RSA·EC) · `writePublicKey` · `writePrivateKey` |
| `SeedUtils` | `generateKey` · `encryptCbc` · `decryptCbc` · `encryptWithIv` · `decryptWithIv` · `encryptEcb` · `decryptEcb` |
| `HmacUtils` | `hmacSha256` · `isEqual` (byte·문자열, 상수 시간) |
| `HashUtils` | `sha256` · `sha256Hex` |
| `KdfUtils` | `pbkdf2Sha256` (salt 비면 거부) |
| `Base64Utils` | `encode` · `decode` · `encodeUrl` · `decodeUrl` (패딩 없는 url-safe) |
| `HexUtils` | `encode`(소문자) · `decode`(대소문자 허용) |

## 에러코드
| 코드 | 상태 | 언제 |
|---|---|---|
| CRYPTO-KEY | 500 | 키 길이·종류 오류, GCM IV 길이 오류, 2048bit 미만 RSA, 암호 걸린 PEM, PBKDF2 null 비밀번호·null/빈 salt |
| CRYPTO-ENCODING | 400 | Base64·Hex·PEM 형식 아님 |
| CRYPTO-ENCRYPT | 500 | 암호화 실패 (null 평문, RSA 데이터가 키 길이보다 김 등) |
| CRYPTO-DECRYPT | 400 | 키 불일치·위변조·패딩 오류 — 원인은 구분하지 않는다(패딩 오라클 방지) |

## 주의
- 새 데이터는 **AES-GCM**. CBC·SEED·RSA PKCS#1 v1.5·SEED-ECB 는 상대 규격 호환용으로만 쓴다.
- 같은 키로 IV 를 재사용하지 않는다 — `encryptWithIv` 가 매번 새로 만든다.
- GCM IV 는 12 byte 만 받는다. 벤더 규격(NICE 인증 등)이 16 byte 인 암호문은 복호화(`decrypt`·`decryptWithIv`)만 받는다.
- RSA 는 짧은 데이터만(2048bit OAEP-SHA256 은 190 byte) — 긴 데이터는 AES 키를 RSA 로 감싼다.
- 비밀번호는 SHA-256 이 아니라 PBKDF2(또는 BCrypt). `HashUtils` 는 검색용 해시·체크섬용이다.
- PBKDF2 는 OWASP 권고(HMAC-SHA256 600,000회 이상, 사용자별 난수 salt 16byte 이상)를 따른다. 반복 횟수 하한은 강제하지 않는다 — 벤더 규격 값(NICE 인증 등)을 그대로 넣어야 해서.
- 키는 코드·yml 에 리터럴로 두지 않는다. 암호 걸린 개인키 PEM 은 받지 않는다 — KMS·환경변수로 평문 PEM 을 넘긴다.
- BouncyCastle 은 엔진만 직접 쓰고 JVM 전역 보안 Provider 를 등록하지 않는다.

## 예제
[`example/crypto`](../../example/crypto) — 개인정보 암호화 고객 (AES-GCM 컬럼, HMAC 이메일 인덱스, PBKDF2 비밀번호)
