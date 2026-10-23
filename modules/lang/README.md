# lang

순수 자바 기본 유틸 — 문자열·컬렉션·난수 문자열·날짜(한국 시간)·개인정보 마스킹.
외부 의존성이 없는 최하단 모듈이고, `common` 을 쓰면 함께 들어온다.

## 의존성
```groovy
implementation 'com.corelib:lang:0.1.0'   // 저장소 설정은 루트 README "설치"
```
설정 없음 — 자동설정도 없다.

## 사용법
```java
StringUtils.isBlank(" ");                         // true (null·공백만)
StringUtils.trimToNull("  ");                     // null
StringUtils.defaultIfBlank(name, "익명");
StringUtils.truncate(text, 100);                  // 로그·예외 메시지 길이 상한

CollectionUtils.emptyIfNull(list).forEach(...);   // null 이어도 바로 순회

RandomStringUtils.randomNumeric(6);               // 인증번호 — SecureRandom
RandomStringUtils.randomAlphanumeric(8);          // 공유 코드

DateTimeUtils.now();                              // 서버 시간대와 상관없이 한국 시각
DateTimeUtils.parseDate("20261024");              // yyyy-MM-dd · yyyyMMdd · yyyy.MM.dd · yyyy/MM/dd
DateTimeUtils.format(LocalDateTime.now());        // "2026-10-24 15:30:00"
DateTimeUtils.age(birthDate);                     // 만 나이

RoundUtils.percentOf(15000, 3.3);                 // 495 (수수료 3.3%, 버림)
RoundUtils.truncateWon(12345, 10);                // 12340 (10원 단위 절사)
RoundUtils.round(new BigDecimal("1234"), -1);      // 1230

MaskingUtils.name("홍길동");                       // 홍*동
MaskingUtils.mobile("01012345678");               // 010-****-5678
MaskingUtils.email("user@example.com");           // us**@example.com
```

## API 요약
| 클래스 | 메서드 |
|---|---|
| `StringUtils` | `isBlank` · `isNotBlank` · `defaultIfBlank` · `trimToNull` · `truncate` |
| `CollectionUtils` | `isEmpty` · `isNotEmpty` (Collection·Map) · `emptyIfNull` |
| `RandomStringUtils` | `randomNumeric` · `randomAlphabetic` · `randomAlphanumeric` |
| `DateTimeUtils` | `now` · `today` · `format` · `parseDate` · `parseDateTime` · `startOfDay` · `endOfDay` · `firstDayOfMonth` · `lastDayOfMonth` · `daysBetween` · `age` · `isBetween` · `fromEpochMilli` · `toEpochMilli` · `toKst`<br>형식 상수 `DATE` · `DATE_COMPACT` · `DATE_TIME` · `DATE_TIME_COMPACT`, 시간대 `KST` |
| `MaskingUtils` | `name` · `mobile` · `email` |
| `RoundUtils` | `round` · `ceil` · `floor` (BigDecimal, scale 음수 = 10원·100원 단위) · `round(double)` · `percentOf` · `rate` · `truncateWon` |

## 주의
- `RandomStringUtils` 는 `SecureRandom` 이라 인증번호·토큰에 써도 된다.
- `DateTimeUtils` 는 엄격 해석이라 2월 30일·13월 같은 값은 `IllegalArgumentException`. 해석 실패 메시지에는 입력값을 30자까지만 넣는다.
- `endOfDay` 는 23:59:59.999999999 — DB 기간 조회는 `>= startOfDay(오늘) AND < startOfDay(내일)` 이 안전하다.
- 금액 계산은 double 이 아니라 `RoundUtils`(BigDecimal) — 0.1 + 0.2 오차가 없다. 할인·수수료는 보통 버림(`floor`·`percentOf`).
- 마스킹은 화면·로그용이다. 원문이 필요한 곳(DB)은 `crypto` 로 암호화한다.

## 예제
[`example/lang`](../../example/lang) — 비회원 게시판 (입력 정리·공유 코드·삭제 PIN·작성자 마스킹)
