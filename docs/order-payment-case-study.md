# 주문·결제 문제 해결 기록

이 문서는 팀 프로젝트에서 제가 담당한 주문·결제 구현과 프로젝트 종료 후 개인 개선을 구분해 설명합니다. 팀 프로젝트의 기준점은 [`team-project-final`](https://github.com/juhwanz/cakeshop_PV/tree/team-project-final) 태그이며, 태그 이후 커밋은 개인 저장소에서 진행한 작업입니다.

## 담당 경계

주문·결제 영역에서는 다음 책임을 맡았습니다.

- 일반 주문과 주문 제작 주문 생성
- 인증 회원 소유권, 서버 기준 가격·금액, 주문·결제 상태 검증
- Toss 결제 화면, success callback과 내부 confirm
- 재고 차감, 결제 완료와 주문 상태 전이의 원자성
- 결제 만료, 고객·관리자 취소, 환불과 실패 복구

회원·상품·쿠폰·매장 데이터는 각 도메인의 공개 Service 계약을 통해 사용하며 해당 도메인의 전체 성과를 개인 기여로 포함하지 않습니다.

## 전체 결제 흐름

```text
주문 생성
  └─ Order.PENDING_PAYMENT + Payment.READY 저장
       └─ 결제 화면: 소유권·상태·만료·서버 금액 검증
            └─ Toss 인증
                 └─ success callback 값 재검증
                      └─ confirm
                           ├─ Toss 조회·승인
                           ├─ 재고 차감·이력
                           ├─ Payment.DONE
                           └─ Order 상태 전이

외부 승인 후 내부 실패
  └─ 영속 보상 요청 → Toss 취소 → 멱등 재시도
```

## 팀 프로젝트 사례 1: 승인 중 결제 만료

### 문제

승인 요청 직전에는 결제 기한이 남았지만 Toss 응답이 만료 뒤 도착하면 기존 흐름은 내부 결제를 완료할 수 있었습니다. 고객 과금과 주문 만료 정책이 충돌하는 시간 경계였습니다.

### 선택

- Toss 승인 뒤 내부 완료 직전에 만료를 다시 검증
- 주문 행 잠금 뒤에도 재검증해 잠금 대기 중 만료되는 경합 차단
- 이미 승인된 결제는 즉시 내부 완료하지 않고 기존 영속 보상 취소로 연결
- 보상 실패·timeout은 스케줄러가 같은 요청을 멱등 재시도

### 결과와 검증 근거

- [팀 Issue #207](https://github.com/team-sweethan/cakeshop/issues/207)
- [팀 PR #209](https://github.com/team-sweethan/cakeshop/pull/209)
- Commit [`e2f101b1`](https://github.com/team-sweethan/cakeshop/commit/e2f101b15ce5baefb8fac15d35683e11c8fd715d)
- PR 기록상 관련 단위 테스트 30건과 MariaDB 통합·경합 테스트 6건 통과
- PR 기록상 전체 테스트는 실행 환경의 Gradle wrapper 잠금 때문에 최종 결과를 확보하지 못했음을 함께 명시

핵심은 “승인 호출 전에 한 번 검사”가 아니라 외부 시스템과 DB 사이의 시간 경계를 두 번 검증하고, 승인 이후 실패에는 복구 가능한 상태를 남긴 것입니다.

## 팀 프로젝트 사례 2: 주문 제작 Toss 선결제

### 문제

일반 주문은 결제 완료 뒤 픽업 준비로 이동하지만 주문 제작은 관리자 검토가 먼저 필요합니다. 같은 Toss 결제 기반을 사용하면서도 도메인별 다음 상태와 쿠폰·재고 처리를 다르게 확정해야 했습니다.

### 선택

- 기존 결제 화면·callback·confirm과 보상 기반 재사용
- `CUSTOM + PENDING_PAYMENT`만 주문 제작 완료 경로로 분기
- 한 트랜잭션에서 재고 차감·이력, `Payment.DONE`, `Order.UNDER_REVIEW`, `Coupon.USED` 확정
- 일반 주문은 기존 `Order.READY_FOR_PICKUP` 전이 유지
- 중복 confirm, 만료, rollback과 승인·만료 경합을 주문 유형 모두에서 검증

### 결과와 검증 근거

- [팀 Issue #225](https://github.com/team-sweethan/cakeshop/issues/225)
- [팀 PR #229](https://github.com/team-sweethan/cakeshop/pull/229)
- 대표 Commit [`06d9033b`](https://github.com/team-sweethan/cakeshop/commit/06d9033b0be19e1a8dbd389cba46c0995ef60f60)
- PR에 기록한 결제·주문·쿠폰 관련 대상 테스트 명령은 통과
- PR 기록상 전체 테스트는 실행 환경이 최종 종료 상태를 반환하지 않아 결과를 확정하지 못했음을 함께 명시

새 결제 체계를 하나 더 만들지 않고 공통 승인·복구 경계를 유지하면서 내부 상태 전이만 주문 유형에 맞게 분리한 것이 핵심입니다.

## 개인 개선 사례: Checkout 검증 공통화

### 기준

이 작업은 팀 프로젝트 종료 태그 이후 개인 저장소에서 진행했습니다. 팀 프로젝트 당시 성과와 구분합니다.

### 문제

주문서 조회와 실제 주문 생성이 비슷한 상품·수량·재고·옵션·금액 검증을 각각 구현했습니다. 그 결과 무제한 재고 상품 11개와 0원 주문은 주문서를 열 수 있지만 제출 시점에야 거부될 수 있었습니다. 복수 장바구니 주문서에서는 매장 정보와 픽업 일정 계산도 반복했습니다.

### 선택

- `GeneralOrderItemPreparationService`로 서버 기준 상품 검증과 금액 계산 공통화
- Checkout과 주문 생성이 같은 검증 결과를 사용
- 장바구니 단위로 매장 조회와 픽업 일정 계산을 한 번만 수행
- 기존 공개 DTO와 주문 생성 트랜잭션 계약 유지

### 결과와 검증 근거

- [개인 Issue #1](https://github.com/juhwanz/cakeshop_PV/issues/1)
- [개인 PR #2](https://github.com/juhwanz/cakeshop_PV/pull/2)
- Commit [`73c961c1`](https://github.com/juhwanz/cakeshop_PV/commit/73c961c1d3273125e52498bea41cd905bdcbbf85)
- PR 기록상 `./gradlew unitTest`, `./gradlew mariaDbTest` 모두 성공
- 무제한 재고 수량 11개 거부, 0원 주문 거부, 복수 장바구니의 매장 조회 1회 검증 추가

이 개선은 정책 자체를 바꾸지 않고, 사용자에게 보여 주는 Checkout과 실제 쓰기 경로가 같은 서버 규칙을 사용하도록 만든 작업입니다.

## 현재 코드에서 확인할 위치

- 주문 생성: [`OrderService`](../src/main/java/com/cakeshop/domain/order/service/OrderService.java)
- Checkout 공통 검증: [`GeneralOrderItemPreparationService`](../src/main/java/com/cakeshop/domain/order/service/checkout/GeneralOrderItemPreparationService.java)
- 결제 화면 검증: [`PaymentCheckoutService`](../src/main/java/com/cakeshop/domain/payment/service/PaymentCheckoutService.java)
- 승인·보상 조정: [`PaymentFacade`](../src/main/java/com/cakeshop/domain/payment/service/PaymentFacade.java)
- Toss 경계: [`TossPaymentClient`](../src/main/java/com/cakeshop/domain/payment/infra/TossPaymentClient.java)
- 결제 복구: [`PaymentRecoveryScheduler`](../src/main/java/com/cakeshop/domain/payment/service/PaymentRecoveryScheduler.java)

전체 컴포넌트와 트랜잭션 경계는 [아키텍처 개요](architecture-overview.md), 현재 로컬 실행에서 Toss와 다른 외부 서비스를 격리하는 방법은 [환경·보안 가이드](security-environments.md)에 정리했습니다. 이 개편의 요구사항과 구현 범위는 개인 저장소 [Issue #3](https://github.com/juhwanz/cakeshop_PV/issues/3)에서 확인할 수 있습니다.
