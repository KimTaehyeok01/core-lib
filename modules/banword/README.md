# modules/banword

Aho-Corasick 기반 금지어·화이트리스트 필터. OKT 형태소 분석(선택적)로 토큰 경계 정렬까지 지원.

## 설치

```groovy
// build.gradle
dependencies {
    implementation 'com.corelib:banword:0.2.0'

    // 형태소 토큰 경계 정렬(getBanWordsWithKeywordExtractor)을 쓰려면 extractor 도 함께
    implementation 'com.corelib:extractor:0.2.0'
}
```

`extractor` 를 넣지 않으면 `getBanWordsWithKeywordExtractor` 호출 시 `CoreException(EXTRACTOR_NOT_AVAILABLE)` 로 떨어집니다. `getBanWords` 는 extractor 없이도 동작합니다.

## BanWordSource 등록 (필수)

라이브러리는 데이터 저장소를 가정하지 않습니다. 소비 앱이 `BanWordSource` 구현 빈을 **반드시** 등록해야 하며, 없으면 기동 시 `CoreException(SOURCE_NOT_REGISTERED)` 로 실패합니다.

### JPA Repository 샘플

```java
@Component
@RequiredArgsConstructor
public class JpaBanWordSource implements BanWordSource {

    private final BanWordRepository banRepo;         // 소비 앱 소유
    private final BanWordAllowRepository allowRepo;  // 소비 앱 소유

    @Override
    @Transactional(readOnly = true)
    public List<String> getBanWords() {
        try {
            return new ArrayList<>(banRepo.findAllWords());  // @Query("select w.word from BanWord w")
        } catch (DataAccessException e) {
            throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "금지어 조회 실패", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getWhiteWords() {
        try {
            return new ArrayList<>(allowRepo.findAllWords());
        } catch (DataAccessException e) {
            throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "화이트리스트 조회 실패", e);
        }
    }
}
```

### 메모리 샘플 (테스트·소규모 배포용)

```java
@Component
public class StaticBanWordSource implements BanWordSource {

    private static final List<String> BAN = List.of("씨발", "병신", "fuck");
    private static final List<String> WHITE = List.of("네비게이션");

    @Override
    public List<String> getBanWords() { return new ArrayList<>(BAN); }

    @Override
    public List<String> getWhiteWords() { return new ArrayList<>(WHITE); }
}
```

### 구현 지침

- 반환 리스트는 **변경 가능한 List** (ArrayList 권장) — `new ArrayList<>(source)` 로 감싸 반환
- 호출 실패는 **`CoreException` 으로 래핑**해 던질 것 (원시 `DataAccessException`/`IOException` 금지)
- null 반환 금지 — 빈 데이터는 `Collections.emptyList()`
- 라이브러리가 Trie 로 로컬 캐시하므로 호출 빈도는 "최초 매칭 + evict 후 다음 매칭" 수준 (대부분 1회성)

## 설정

```yaml
core:
  banword:
    max-text-length: 10000   # 입력 문자열 길이 상한 (기본 10000, 범위 1~1_000_000)
```

초과 입력은 `CoreException(TEXT_TOO_LONG)` (HTTP 400).

## 사용

```java
@RequiredArgsConstructor
public class CommentService {

    private final BanWordUtils banWordUtils;

    public void writeComment(String content) {
        List<String> hits = banWordUtils.getBanWords(content);
        if (!hits.isEmpty()) {
            throw new CoreException(MyError.BANNED_WORD, "금지어 포함: " + hits);
        }
        // ...
    }

    public void writeCommentStrict(String content) {
        // 형태소 토큰 경계 정렬까지 — extractor 모듈 필요
        List<String> hits = banWordUtils.getBanWordsWithKeywordExtractor(content);
        // "네비게이션" 안의 "게이" 같은 우연 매칭 자동 제외 (토큰 경계 불일치)
        // 화이트리스트 Emit 구간 포함 시에도 제외
    }
}
```

## 캐시 분기 (Redis / Direct)

**Redis 모드** — 다음 두 조건 충족 시 자동 선택:
- 클래스패스에 `org.springframework.data.redis.cache.RedisCacheManager`
- `RedisCacheManager` 타입 빈 등록됨 (`corelib:redis` 는 `cacheManager` 이름으로 자동 등록 + `spring.data.redis.*` 설정 필요)

Redis 모드에서는 `BanWordSource.getBanWords/getWhiteWords` 결과를 Redis 캐시(`banWords`/`whiteWords` key=`all`)에 저장, `BanWordCacheService.evictBanWords/evictWhiteWords` 호출 시 캐시 삭제 + Matcher Trie 재구성.

**Direct 모드** — Redis 조건 미충족 시 폴백. 캐시 없이 매 재구성 요청마다 `BanWordSource` 호출.

### 다중 인스턴스 주의

Redis 모드라도 **로컬 Trie 는 각 JVM 에 독립**입니다. evict API 는 호출받은 인스턴스의 Trie 만 무효화합니다.

**운영 중 금지어 추가/삭제 시 모든 인스턴스의 evict API 를 호출**해야 합니다. 라이브러리는 Redis Pub/Sub 등 자동 전파를 하지 않습니다.

```java
// 소비 앱 Admin Controller 샘플
@PostMapping("/admin/banword/evict")
public ApiResponse<Void> evict() {
    banWordCacheService.evictBanWords();
    banWordCacheService.evictWhiteWords();
    return ApiResponse.ok();
}
```

LB 뒤 N 대 환경이면 N 개 인스턴스 각각에 요청을 보내거나, 소비 앱 레벨에서 자체 브로드캐스트 메커니즘(예: 소비 앱의 Pub/Sub, 메시지 큐)을 구축해야 합니다.

## 금지어 등록 규칙 (중요)

라이브러리는 입력에 다음 전처리를 적용한 뒤 매칭합니다.
1. 소문자 변환
2. Leetspeak 치환 (`0→o`, `1→i`, `3→e`, `4→a`, `5→s`, `7→t`, `@→a`, `$→s`)
3. 공백 제거
4. 특수문자 필터 (한글·영문·자음·모음만 유지)
5. (형태소 메서드만) OKT normalize — 반복 자모 축약

**금지어·화이트 원문도 같은 전처리를 거쳐 Trie 에 등록**됩니다. 따라서 DB 에 등록할 때 전처리 결과를 유추해 등록하세요.

| DB 원문 | Trie 에 등록되는 정규화 형태 | 매칭되는 입력 예시 |
|---|---|---|
| `"씨발"` | `"씨발"` | `"씨발"`, `"씨 발"`, `"씨!발"` |
| `"FUCK"` | `"fuck"` | `"fuck"`, `"FUCK"`, `"F*U*C*K"` |
| `"ㅆ1발"` | `"ㅆi발"` | `"ㅆ1발"`, `"ㅆI발"` |
| `"!@#$"` | (전처리 후 `"a s"` → 공백 제거 후 `"as"`) | — |
| `"🙈"` | (빈 문자열) → **skip** | 등록 불가 |

전처리 결과가 빈 문자열인 금지어는 Trie 에 들어가지 않으므로 등록이 무의미합니다.

`BanWordUtils.getBanWords` 는 매칭 시 **원형(DB 저장값)을 반환**합니다. 전처리로 변환된 값이 아님에 주의.

## Scunthorpy 문제 공지

공백 제거 전처리 때문에 `"this is a class"` → `"thisisaclass"` 에서 영문 금지어 `"ass"` 가 매칭됩니다 (경계 검사 skip 되는 영문 전용 금지어 대상). 이는 설계 수용 사항이며, 완화하려면.
- 영문 금지어를 공백 포함 완전 구문으로 등록 (`"you ass"`)
- 또는 화이트리스트에 `"class"` 등록 → Emit 구간 포함 비교로 자동 제외

`getBanWordsWithKeywordExtractor` 는 토큰 경계 정렬이 있어 Scunthorpy 가능성이 낮습니다.

## Error 코드

| Error | HTTP | 상황 |
|---|---|---|
| `BanWordError.SOURCE_NOT_REGISTERED` (E910) | 500 | 기동 시 `BanWordSource` 빈 없음 |
| `BanWordError.TEXT_TOO_LONG` (E911) | 400 | 입력이 `max-text-length` 초과 |
| `BanWordError.EXTRACTOR_NOT_AVAILABLE` (E912) | 500 | `getBanWordsWithKeywordExtractor` 호출 시 `extractor` 모듈 미설치 |
| `BanWordError.INVALID_MAX_TEXT_LENGTH` (E913) | 500 | 기동 시 `max-text-length` 범위 위반 (1~1_000_000) |

## 제한 사항

- 소비 앱 Source 구현이 매 호출마다 DB 전체 조회 — 라이브러리 Trie 가 캐시해 호출 빈도는 낮지만, Source 구현 자체가 `new ArrayList<>(findAll())` 를 반환하면 호출 시마다 전체 Entity 매핑 비용 발생. projection(`@Query("select w.word from BanWord w")`) 권장
- 대용량 입력은 `max-text-length` 로 보호 — 운영 환경에 맞게 조정
- OKT (open-korean-text) 사전은 Scala 트랜지티브로 약 5MB. 형태소 미사용 소비 앱은 `extractor` 모듈을 뺄 수 있음
