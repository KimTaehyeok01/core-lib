# redis

Redis 자료구조 유틸(`RedisUtils`) · Spring Cache(`@Cacheable`) · Redisson 분산 락(`@RedisLock`).  
접속은 표준 `spring.data.redis.*` 를 그대로 쓴다 (Lettuce 커넥션과 Redisson 이 같은 대상에 붙는다).

## 의존성
```groovy
implementation 'com.corelib:redis:0.2.0'   // 저장소 설정은 루트 README "설치"
```
`redisson` 3.52.0 과 `spring-data-redis` 는 전이로 들어온다. 소비 앱이 `@Cacheable` 을 직접 쓰려면 `spring-boot-starter-cache` 가 이미 포함된다.

> **[필수] 소비 앱의 `-parameters` 컴파일 옵션** — `@RedisLock(key = "#id")` SpEL 이 파라미터 이름을 읽어야 작동한다.
> - **Spring Boot Gradle 플러그인**을 쓰는 앱은 자동으로 켜져 있다.
> - **순수 Gradle / Maven** 은 명시 필요:
>   ```groovy
>   tasks.withType(JavaCompile).configureEach {
>       options.compilerArgs << '-parameters'
>   }
>   ```
> 안 켜져 있으면 `@RedisLock` 호출 시 `500 INTERNAL_SERVER` 로 가드가 즉시 실패시킨다 (조용히 통과 안 함).

## 설정
### Standalone (기본)
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password: ${REDIS_PASSWORD:}   # 선택
core:
  redis:
    key-prefix: my-service           # 선택 — 모든 키·캐시·락 앞에 "{prefix}:"
    trusted-packages:                # 선택 — 커스텀 DTO 를 캐시·값 저장에 쓰려면 패키지 허용 (기본 java.lang/util/time/math)
      - com.myapp.domain
    lock:
      long-hold-warn-threshold: 60s  # 선택 — 락 장기 보유 경고. 0 이면 끔
```
### Sentinel / Cluster
```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes:
          - 10.0.0.1:26379
          - 10.0.0.2:26379
        password: ${REDIS_PASSWORD}
      # 또는
      cluster:
        nodes: [10.0.0.21:6379, 10.0.0.22:6379]
        password: ${REDIS_PASSWORD}
```
`spring.data.redis` 의 토폴로지 키(`host`·`url`·`sentinel.nodes`·`cluster.nodes`) 중 하나라도 값이 있으면 모듈이 활성화된다. **빈 값(`host: ""`)은 기동 실패** — 안 쓰는 키는 지운다.
접속 키가 전부 미설정이면 모듈은 조용히 꺼진다 (호출 시 "미초기화" 응답, 기동 안내에 표시).

## 사용법
### 자료구조 (`RedisUtils`)
```java
// String
RedisUtils.Strings.set("greeting", "안녕", Duration.ofMinutes(10));
Object v = RedisUtils.Strings.get("greeting");
long n = RedisUtils.Strings.increment("visits");
RedisUtils.Strings.setLong("stock:1", 100);   // 카운터는 setLong — JSON 타입정보 없이 raw 로 저장 (INCR/DECR 함정 회피)

// Set / List / Hash / ZSet
RedisUtils.Sets.add("tags", "redis", "cache");
RedisUtils.Lists.pushLast("recent", event);
RedisUtils.Hashes.putAll("cart", Map.of("item-1", 2, "item-2", 1));
RedisUtils.ZSets.incrementScore("ranking", "spring", 1);
List<ScoredValue> top = RedisUtils.ZSets.top("ranking", 10);

// 키 공통
boolean exists = RedisUtils.exists("greeting");
List<String> keys = RedisUtils.keys("cart:*", 100);  // SCAN 기반 — limit 필수
```

### 캐시 (`@Cacheable`)
```java
@Cacheable(cacheNames = "product", key = "#id")
public ProductDto find(Long id) { ... }
```
캐시 키 형식: `{key-prefix}:{cacheName}::{key}`. 자세한 TTL/eviction 은 `RedisCacheManagerBuilderCustomizer` 빈으로.

### 분산 락 (`@RedisLock`)
```java
// REQUEST scope (기본) — 같은 엔드포인트 안에서 중복 요청 차단
@PostMapping("/orders/{id}/pay")
@RedisLock(key = "#accountId + ':' + #id")
public ApiResponse<Void> pay(Long accountId, Long id) { ... }

// GLOBAL scope — 진입점(다른 API · @Async · 배치) 무관 같은 자원 배제
@RedisLock(key = "'coupon:' + #couponId", scope = LockScope.GLOBAL)
public IssueResult issue(Long couponId, Long memberId) { ... }

// 운영 중 yml 로 대기/보유 시간 조정 (기동 시 검증)
@RedisLock(key = "#orderNo",
    waitTimeString = "${app.order-lock.wait:3s}",
    leaseTimeString = "PT75S")
public void confirm(String orderNo) { ... }

// 실패해도 진행해야 하는 경로 (중복 실행 안전한 집계·캐시 예열 등)
@RedisLock(key = "'stats:' + #day", waitTime = 0, failOpen = true)
public void recompute(String day) { ... }
```

## @RedisLock 옵션
| 속성 | 기본값 | 설명 |
|---|---|---|
| `key` | (필수) | SpEL — `#파라미터명` 또는 `#p0`. 결과가 blank 면 `400 BAD_REQUEST` |
| `waitTime` | `3` | 대기 시간(초). 넘기면 `409 LOCK_CONFLICT` (`failOpen` 이면 락 없이 진행) |
| `leaseTime` | `-1` | 보유 상한(초). `-1`/`0` = 워치독 자동 연장 (메서드 종료 시 해제). 양수 = 그 시간 뒤 자동 해제 |
| `timeUnit` | `SECONDS` | `waitTime`/`leaseTime` 단위 |
| `scope` | `REQUEST` | `REQUEST`: 매칭 URI 패턴별 (같은 엔드포인트 안에서). `GLOBAL`: 진입점 무관 |
| `waitTimeString` | `""` | 공백 아니면 `waitTime` 보다 우선. `${...}` 해석. `500ms`·`75s`·`PT75S` |
| `leaseTimeString` | `""` | 공백 아니면 `leaseTime` 보다 우선. 워치독 지정은 `"-1ms"` 또는 `"0"` (단위 없는 `"-1"` 은 `-1s` 로 해석되어 오류) |
| `failOpen` | `false` | 락 미획득 시 락 없이 진행. **중복 실행 안전한 경로에만** |

## API 요약
| 메서드 | 내용 |
|---|---|
| `RedisUtils.exists / delete / expire / getExpire / keys` | 키 공통 |
| `RedisUtils.Strings.{get, set, setLong, setIfAbsent, increment, decrement}` | String·카운터 |
| `RedisUtils.Sets.{members, size, contains, add, remove}` | Set |
| `RedisUtils.Lists.{range, all, pushFirst, pushLast, popFirst, popLast, trim, remove}` | List |
| `RedisUtils.Hashes.{entries, get, put, putAll, multiGet, hasField, increment, delete}` | Hash |
| `RedisUtils.ZSets.{add, incrementScore, score, rank, range, top, rangeByScore, remove, removeRange}` | ZSet (랭킹) |

## 에러코드
| 코드 | 상태 | 언제 |
|---|---|---|
| `REDIS-LOCK` | 409 | `@RedisLock` 대기 초과 · 대기 중 인터럽트 (잠시 뒤 재시도) |
| `REDIS-UNAVAILABLE` | 500 | Redis 연결 실패·타임아웃, `RedisUtils` 미초기화 (재시도로 안 풀림 — 인프라 확인) |

## 주의
- **-parameters** 필수 (위 "의존성" 참조). 안 켜면 `@RedisLock` 호출마다 500.
- **Redis 장애 시 `@Cacheable` 은 폴백 없이 500 + 최대 10초 스레드 점유** — 캐시가 단일 장애점이 된다. 트래픽 많은 서비스는 `RedisCacheManagerBuilderCustomizer` 로 `CacheErrorHandler`(원본 메서드 폴백) 를 교체해야 한다.
- **캐시 기본 TTL 없음** — 소비자가 `RedisCacheManagerBuilderCustomizer` 로 TTL 지정 안 하면 모든 캐시 키가 영구 보존. 임의 id 로 조회되는 엔드포인트는 메모리가 계속 늘어난다.
- **카운터는 `setLong`/`increment` 로** — `Strings.set(Object)` 로 저장한 Long 은 JSON 타입 정보(`["java.lang.Long",3]`) 때문에 INCR/DECR 가 `ERR value is not an integer` 로 실패한다.
- **scope=REQUEST 가 웹 밖**(`@Async`·스케줄러·배치)에서 호출되면 `lock:uri:{key}` 단일 네임스페이스로 수렴 — 서로 무관한 호출이 같은 key 값이면 충돌. 웹 밖에서는 `scope = LockScope.GLOBAL` 쓰거나 key 값을 전역 유일하게 만든다.
- **scope=REQUEST 는 spring-webmvc + jakarta.servlet 가 classpath 에 필요**. 없으면 "uri" fallback. 비웹 앱은 `scope = LockScope.GLOBAL` 명시.
- **락 키는 로그에 남는다** — PII(전화·이메일) 는 `key` 로 쓰지 말고 내부 식별자(memberId·주문번호)를 쓴다. 사용자 입력 String 은 사전 정제. 라이브러리가 제어문자는 `_` 로 치환하고 200자로 자른다.
- `@RedisLock` 은 같은 프록시의 `@Transactional` **바깥**에서 돈다 — 락 획득 → 트랜잭션 → 커밋 → 락 해제. 호출자 메서드에 걸린 트랜잭션은 락 바깥이라 커밋 전에 락이 풀린다 (락 메서드 자체가 트랜잭션 경계).
- 같은 스레드 재진입만 허용. 같은 키를 다른 스레드(@Async 등)에서 기다리면 `waitTime` 후 409.

## 예제
[`example/redis`](../../example/redis) — 쇼핑몰 3기능 (포트 8118):
- `GET /products/find?id=1` — 상품 상세 캐시 (`@Cacheable`)
- `POST /coupons/issue` — 선착순 쿠폰 분산 락 (`@RedisLock` scope=GLOBAL + `RedisUtils.Strings.setLong`/`decrement` 재고 원자 차감)
- `POST /trending/record` + `GET /trending/top` — 인기 검색어 ZSet
