# example-crypto

`modules/crypto` 로 만든 **개인정보 암호화 고객 서비스** 예제 — 가입·로그인·휴대폰 변경.

## 실행
```bash
# 키(선택) — base64 32byte. 비우면 기동할 때 임시 키를 만든다 (재시작하면 이전 데이터는 못 푼다)
export CRYPTO_AES_KEY=...
export CRYPTO_INDEX_KEY=...
./gradlew :example:crypto:bootRun
```
```bash
curl -X POST localhost:8103/api/customers/join -H 'Content-Type: application/json' \
  -d '{"name":"홍길동","email":"hong@example.com","mobileNo":"010-1234-5678","password":"pass-word-1"}'
curl -X POST localhost:8103/api/customers/login -H 'Content-Type: application/json' -d '{"email":"HONG@example.com","password":"pass-word-1"}'
curl localhost:8103/api/customers/stored-rows     # 저장소에 원문이 없는지 확인 — CRYPTO_DEBUG_ENDPOINTS=true 로 띄웠을 때만 (기본 404)
```

## 서비스가 쓰는 crypto 유틸
| 저장 컬럼 | 방식 | 유틸 |
|---|---|---|
| 이름·이메일·휴대폰 | AES-256-GCM, 매번 새 IV, `base64url(IV‖암호문)` | `AesUtils.encryptWithIv / decryptWithIv`, `Base64Utils` |
| 이메일 검색 인덱스 | `HMAC-SHA256(인덱스 키, 소문자 이메일)` — 중복확인·로그인 조회 | `HmacUtils.hmacSha256` |
| 비밀번호 | PBKDF2-HmacSHA256 60만 회 + 고객별 salt, 상수 시간 비교 | `KdfUtils.pbkdf2Sha256`, `HmacUtils.isEqual` |
| 화면 응답 | 복호화 후 마스킹 | `MaskingUtils` (lang) |

로그인 제한 — 이메일당 5번 틀리면 5분 잠금(`429 CUSTOMER_LOGIN_LOCKED`, 잠금 중엔 비밀번호 계산 안 함). 없는 이메일도 더미 salt 로 같은 PBKDF2 를 계산하고 똑같이 잠근다.

## 소비 프로젝트에 옮길 것
- `service/CustomerExampleService` — 고객 테이블만 DB 로 바꾸면 된다 (`email_index` 에 unique). 실패 횟수는 Redis(INCR + TTL)·테이블
- 암호화 키와 인덱스 키는 분리, 환경변수/KMS 로만 주입
- 로그인 실패는 이메일 없음·비밀번호 틀림을 같은 응답·같은 계산량으로 (가입 여부 노출 방지)
- 휴대폰 변경 같은 부분 수정은 바꿀 컬럼만 UPDATE — 읽은 행 전체를 덮어쓰지 않는다
- `/stored-rows` 는 예제 확인용이다 (`example.crypto.debug-endpoints`, 기본 false) — 실서비스에는 두지 않는다
