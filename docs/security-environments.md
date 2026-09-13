# 환경·보안 가이드

이 문서는 실행 프로필, 외부 연동 활성 조건과 공개 저장소의 비밀 관리 경계를 설명합니다. 도메인의 인증·인가·트랜잭션 규칙은 [`docs/conventions.md`](conventions.md), 실제 실행 절차는 [로컬 실행 가이드](getting-started.md)가 담당합니다.

## 기본 원칙

- 기본 프로필을 두지 않는다. 실행자가 DB 환경을 명시하지 않으면 시작을 거부한다.
- `local` 단독 실행은 실제 외부 서비스를 호출하지 않는다.
- 외부 서비스 키가 환경 변수에 남아 있어도 활성 프로필이 없으면 사용하지 않는다.
- 공개 샘플에는 실제 비밀과 팀 인프라의 bucket·endpoint·URL을 기록하지 않는다.
- `preview`는 외부 호출뿐 아니라 예약 작업도 끈다.
- Toss 테스트 호출은 `local,toss-test`에서만 예외적으로 허용하며 라이브 키를 거부한다.

## 허용 프로필

| 조합 | DB·파일 | 외부 호출 | 예약 작업 | 용도 |
|---|---|---|---|---|
| 없음 | 시작 거부 | 없음 | 없음 | 암묵적 환경 선택 방지 |
| `local` | 로컬 MariaDB·로컬 파일 | 없음 | 활성 | 일반 개발 |
| `local,preview` | 로컬 MariaDB·로컬 파일 | 없음 | 비활성 | 익명 조회 시연 |
| `local,toss-test` | 로컬 MariaDB·로컬 파일 | Toss 테스트 API만 | 결제 복구 포함 활성 | 결제 연동 확인 |
| `test` | 테스트가 제공 | 테스트 대역만 | 테스트가 제어 | 자동화 테스트 |

`rds`, `s3`, `oauth`, `smtp`, `sms` 운영 연동용 설정은 남아 있지만 이 문서는 운영 사용의 안전성을 보증하지 않습니다. 운영 RDS·운영 S3와 Toss 웹훅 정책은 별도 합의 대상입니다.

## 거부하는 조합

시작 단계의 `RuntimeProfilePolicy`가 다음 조합을 Bean 생성 전에 거부합니다.

- `local,rds`: DB 대상이 둘이거나 둘 다 없는 실행
- `preview` without `local`
- `local` + `s3`, `oauth`, `smtp`, `sms`
- `preview,toss-test`
- `toss-test` without `local`
- `test` + 실행·외부 연동 프로필

Spring이 읽은 활성 프로필을 기준으로 검사하므로 환경 변수나 실행 인자로 우회해도 같은 규칙이 적용됩니다.

## 외부 연동별 경계

| 연동 | 비활성 경계 | 활성 조건 |
|---|---|---|
| Toss | `app.payment.toss.enabled=false`, 복구 스케줄러 미등록 | `local,toss-test`와 유효한 테스트 키 쌍 |
| SMTP | `DisabledEmailSender` 등록, Spring Mail 설정 미로딩 | `smtp` 프로필. `local`과 조합 불가 |
| Solapi SMS | `app.kakao.alimtalk.enabled=false` | `sms` 프로필. `local`과 조합 불가 |
| S3 | `LocalFileStorageClient` 사용 | `s3` 프로필. `local`과 조합 불가 |
| OAuth | OAuth ClientRegistration 미로딩 | `oauth` 프로필. `local`과 조합 불가 |
| Scheduler | 일반 실행에서 등록 | `preview`에서는 전체 미등록 |

SMTP가 비활성인 로컬에서는 이메일 인증 요청이 안전한 발송 실패로 처리됩니다. 인증번호를 로그로 출력하지 않으며, 기능 확인에는 seed 계정을 사용합니다.

## Toss 테스트 키 검증

`toss-test`는 다음 접두의 키만 허용합니다.

- Client key: `test_ck_`, `test_gck_`
- Secret key: `test_sk_`, `test_gsk_`

Client/Secret 중 하나가 없거나 두 키 유형이 일치하지 않거나 `live_ck_`, `live_gck_`, `live_sk_`, `live_gsk_` 등 테스트 접두가 아닌 값이면 시작을 거부합니다. Secret key와 Authorization 헤더는 응답이나 로그에 기록하지 않습니다.

## 비밀 관리

- `.env_sample`: 변수 이름과 비식별 placeholder만 커밋
- `.env`: 개인·환경별 실제 값, Git 제외
- 배포 환경: 저장소 밖의 비밀 저장소나 런타임 환경 변수 사용
- 유출 의심 시: 값을 삭제하는 것만으로 끝내지 않고 공급자에서 키를 폐기·재발급

설정 구현은 [`application.yml`](../src/main/resources/application.yml), 프로필 조합 검증은 [`RuntimeProfilePolicy`](../src/main/java/com/cakeshop/global/config/RuntimeProfilePolicy.java)에서 확인할 수 있습니다.
