# payment/kspay

KSNET KSPAY — 승인은 **통합모듈 V1.4**(결제창 → web_host, MS949 백틱 응답), 취소는 **webfep REST**(카드·계좌이체·휴대폰·가상계좌 회수).

## 의존성
```groovy
implementation 'com.corelib:payment-kspay:0.2.0'   // 저장소 설정은 루트 README "설치"
```

## 설정
```yaml
payment:
  kspay:
    mid: ${KSPAY_MID}                  # 상점아이디 — 없으면 자동설정이 꺼진다 (공식 샘플 테스트 상점 2999199999)
    api-key: ${KSPAY_API_KEY}          # webfep pgapi 키 (KSTA > PG상점정보) — 취소에만 필요, 없으면 취소만 안 된다
    approval-base-url: https://kspay.ksnet.to   # 기본값 (비우면 기본값)
    webfep-base-url: https://paydev.ksnet.co.kr # 개발. 운영은 생략·빈 값(https://pay.ksnet.co.kr)
    connect-timeout: 5s
    read-timeout: 30s
```

## 사용법
```java
// 결제창 → sndReply 페이지가 opener 로 넘긴 결제키(reCommConId)로 승인 — 금액은 서버 보관 값
KsPayDto.Approval approval = KsPayUtils.approve(KsPayDto.ApproveRequest.builder()
    .payKey(reCommConId).orderNo(order.getOrderNo()).amount(order.getAmount())
    .channel(KsPayDto.Channel.PC)    // 모바일 결제창이면 MOBILE
    .build());
// approval.getTid(), approval.getPayMethod() 를 저장 — 취소에 쓴다

// 승인 불일치(KSPAY-ORDER·AMOUNT)는 승인이 성립한 상태 — 예외의 tid·payMethod 로 바로 자동취소
try {
    approval = KsPayUtils.approve(request);
} catch (KsPayMismatchException e) {
    if (e.isCancelable()) {    // KSPAY-MID(다른 상점 승인)는 우리 api-key 로 취소되지 않아 false
        KsPayUtils.cancel(KsPayDto.CancelRequest.builder()
            .payMethod(e.getPayMethod()).tradeKeyType(KsPayDto.TradeKeyType.TID).tradeKey(e.getTid()).build());
    }
    throw e;
}

// 전액 취소
KsPayUtils.cancel(KsPayDto.CancelRequest.builder()
    .payMethod(approval.getPayMethod()).tradeKeyType(KsPayDto.TradeKeyType.TID).tradeKey(approval.getTid()).build());
// 부분 취소 (카드·계좌이체만, cancelSeq 1~9)
KsPayUtils.cancel(KsPayDto.CancelRequest.builder()
    .payMethod(KsPayDto.PayMethod.CARD).tradeKeyType(KsPayDto.TradeKeyType.TID).tradeKey(tid)
    .cancelType(KsPayDto.CancelType.PARTIAL).cancelAmount(3000).cancelSeq(1).build());
```

## 동작 원칙
- 승인 응답의 **상점아이디·주문번호·금액**을 대조한다 — 다르면 `KSPAY-MID`·`ORDER`·`AMOUNT` (승인 성립 상태).
- 승인이 결과불명이면 **같은 결제키로 1회 재전송**해 결과를 다시 받는다 (webfep 에 조회 API 가 없다). 그래도 모르면 `KSPAY-UNKNOWN`.
- 주문번호는 PC 50byte·모바일 30byte(MS949) 이내, `` ` ~ ' " `` 금지 — 넘으면 보내기 전에 막는다.
- 취소 성공 = `code=A0200` **그리고** `respCode` 가 0 으로만 이뤄짐(카드 `0000`, 계좌이체 `000`). 결과불명이어도 자동 재시도하지 않는다.
- 취소 4xx 응답도 본문 `code` 를 읽어 `KSPAY_{code}` 로 넘긴다 (예: `KSPAY_A0401` 인증 오류 → 500). 본문이 webfep 규격이 아닐 때만 `KSPAY-EXTERNAL`, `A0500`·`A0999` 는 결과불명.
- KSPAY 결과코드는 `KSPAY_{code}` 로 넘긴다.
- 호출은 동기(`.block()`)이고 한 번에 최대 `connect-timeout + read-timeout + 5초` 걸린다 (읽기 timeout 이 read 마다 다시 걸려 끝나지 않는 응답을 막는 전체 상한, 초과는 결과불명). **`@Transactional`·DB 커넥션 점유 구간 밖에서 호출한다.** 승인 결과불명이면 재전송까지 최대 2배.

## API 요약
| 메서드 / 타입 | 내용 |
|---|---|
| `approve(ApproveRequest{payKey, orderNo, amount, channel})` | `Approval` — `tid` · `amount` · `orderNo` · `storeId` · `approvalNo` · `tradedAt` · `cardNo`(마스킹) · `isSuccess()` · `getPayMethod()` |
| `cancel(CancelRequest{payMethod, tradeKeyType, tradeKey, tradeDt, cancelType, cancelAmount, cancelSeq, taxAmount, taxFreeAmount, payload})` | `CancelResponse` — `data.tid` · `data.cancelAmount` · `isSuccess()` |
| `KsPayMismatchException` | 승인 불일치 예외 (`CoreException` 하위) — `getTid()` · `getPayMethod()` · `isCancelable()` |
| `PayMethod` | `CARD` · `ACCOUNT` (부분취소 가능) · `MOBILE`(당월·전액만) · `VACCOUNT`(미입금 회수) |
| `TradeKeyType` | `TID` · `ORDER_NUMB`(원거래일자 `tradeDt` 필수) |

## 에러코드
| 코드 | 상태 | 언제 / 할 일 |
|---|---|---|
| `KSPAY_{code}` | 400 | 승인·취소 거절 |
| KSPAY-MID · KSPAY-ORDER · KSPAY-AMOUNT | 400 | 승인 응답이 주문과 다름 (`KsPayMismatchException`) — ORDER·AMOUNT 는 tid 로 **즉시 취소**, MID 는 KSTA 확인 |
| KSPAY-EXTERNAL | 502 | 결제키 무효·만료, 연결 실패, 취소 4xx 본문 해석 불가 |
| KSPAY-UNKNOWN | 502 | 재전송으로도 확인 불가 — KSTA 관리자 확인 |

## 주의
- 결제수단을 모르는 승인(`getPayMethod()` null)은 취소 경로를 정할 수 없다 — 불일치 예외도 `isCancelable()` false, KSTA 에서 확인한다.
- MS949 응답을 읽으려면 런타임에 `jdk.charsets` 가 있어야 한다 (일반 JDK 는 포함, 최소 jlink 이미지면 추가).

## 예제
[`example/payment/kspay`](../../../example/payment/kspay) — KSNET 통합모듈 V1.4 공식 샘플 구조의 결제창 → 수신 페이지 → 승인 → 부분·전액 취소
