# 로컬 실행 가이드

이 문서는 cakeshop을 외부 서비스 호출 없이 로컬에서 실행하는 절차를 설명합니다. 프로젝트의 핵심과 개인 기여는 [`README.md`](../README.md), 프로필별 보안 경계는 [환경·보안 가이드](security-environments.md)를 먼저 확인할 수 있습니다.

## 1. 준비 사항

| 도구 | 용도 |
|---|---|
| Git | 저장소 내려받기 |
| JDK 21 | 애플리케이션 빌드와 실행 |
| MariaDB 11.4 | 로컬 데이터베이스 |
| Docker | MariaDB Testcontainers 테스트를 실행할 때만 필요 |

Gradle은 저장소의 Wrapper를 사용하므로 별도로 설치하지 않습니다. 애플리케이션만 실행할 때는 Docker가 필요하지 않습니다.

## 2. 저장소와 환경 파일 준비

```bash
git clone https://github.com/juhwanz/cakeshop_PV.git
cd cakeshop_PV
cp .env_sample .env
```

Windows PowerShell에서는 다음 명령을 사용합니다.

```powershell
git clone https://github.com/juhwanz/cakeshop_PV.git
Set-Location cakeshop_PV
Copy-Item .env_sample .env
```

`.env`에서 다음 로컬 DB 값만 먼저 설정합니다.

```dotenv
LOCAL_DB_HOST=localhost
LOCAL_DB_PORT=3306
LOCAL_DB_DATABASE=cakeshop
LOCAL_DB_USERNAME=your-local-user
LOCAL_DB_PASSWORD=your-local-password
FILE_UPLOAD_DIR=/absolute/path/to/cakeshop-uploads
```

`.env`는 Git에서 제외됩니다. 실제 키·비밀번호를 `.env_sample`, README, 로그나 Issue에 기록하지 않습니다.

## 3. 로컬 데이터베이스 생성

MariaDB에서 다음 SQL을 한 번 실행합니다. 테이블과 제약조건은 애플리케이션 시작 시 Flyway가 적용합니다.

```sql
CREATE DATABASE `cakeshop`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
```

애플리케이션 계정에는 로컬 `cakeshop` 데이터베이스의 DDL과 읽기·쓰기 권한이 필요합니다.

## 4. 외부 호출 없이 실행

macOS와 Linux:

```bash
./gradlew bootRun --args="--spring.profiles.active=local"
```

Windows:

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

`local`은 다음 경계를 강제합니다.

- 로컬 MariaDB와 로컬 파일 저장소만 사용
- Toss 결제 화면과 승인·조회·취소 호출 비활성
- SMTP 대신 `DisabledEmailSender`를 사용해 실제 메일 발송 거부
- Solapi SMS 비활성
- S3와 OAuth 프로필 조합 거부
- Prometheus 지표의 익명 공개 비활성

따라서 로컬에서는 이메일 인증이 필요한 신규 가입 대신 seed 계정을 사용합니다.

## 5. 샘플 데이터와 화면 확인

애플리케이션을 한 번 실행해 Flyway 적용을 마친 뒤 MariaDB 클라이언트에서 필요한 seed를 순서대로 실행합니다.

```sql
SOURCE src/main/resources/db/seed/seed-local.sql;
SOURCE src/main/resources/db/seed/seed-community.sql;
SOURCE src/main/resources/db/seed/seed-review.sql;
```

`seed-community.sql`과 `seed-review.sql`은 해당 화면을 확인할 때만 필요합니다.

| 역할 | 계정 | 비밀번호 |
|---|---|---|
| 고객 | `user@cakeshop.local` | `Admin1234!` |
| 관리자 | `admin@cakeshop.local` | `Admin1234!` |

샘플 계정은 로컬 확인 전용이며 공용·운영 환경에서 사용하지 않습니다.

## 6. 공개 미리보기

일부 고객 조회 화면을 로그인 없이 확인하려면 다음 조합을 사용합니다.

```bash
./gradlew bootRun --args="--spring.profiles.active=local,preview"
```

`preview`는 `local`과만 조합할 수 있습니다. 모든 `@Scheduled` 작업이 등록되지 않고, Toss·SMTP·SMS·S3·OAuth 외부 호출도 활성화되지 않습니다. 관리자 화면과 쓰기 동작은 기존 인증·인가 정책을 유지합니다.

## 7. Toss 테스트 결제

Toss 테스트 API 호출은 안전한 기본 로컬 실행의 예외이므로 명시적으로 선택해야 합니다.

1. `.env`에 같은 테스트 자격 증명 쌍을 입력합니다.

   ```dotenv
   TOSS_CLIENT_KEY=test_ck_...
   TOSS_SECRET_KEY=test_sk_...
   ```

2. 다음 프로필로 실행합니다.

   ```bash
   ./gradlew bootRun --args="--spring.profiles.active=local,toss-test"
   ```

`test_gck_`/`test_gsk_` 쌍도 허용합니다. 키가 하나만 있거나 라이브 키인 경우 애플리케이션은 시작 단계에서 실패합니다. `preview`와 `toss-test`는 함께 사용할 수 없습니다.

## 8. 테스트

```bash
./gradlew unitTest
./gradlew mariaDbTest
```

- `unitTest`: MariaDB Testcontainers 태그를 제외한 테스트
- `mariaDbTest`: Docker 기반 MariaDB 통합 테스트
- `test`: 두 범위를 모두 실행

세부 테스트 책임과 선택 기준은 [테스트 작성 가이드](testing.md)를 따릅니다.

## 9. 자주 발생하는 문제

- `실행 프로필이 없습니다`: `--spring.profiles.active=local`을 명시합니다.
- `Access denied`: `.env`의 로컬 MariaDB 계정과 권한을 확인합니다.
- `Unknown database 'cakeshop'`: 데이터베이스 생성 SQL을 먼저 실행합니다.
- `Port 8080 was already in use`: 기존 애플리케이션 프로세스를 종료하거나 포트를 변경합니다.
- `안전하지 않은 실행 프로필 설정`: [허용 프로필 조합](security-environments.md#허용-프로필)을 확인합니다.
- Testcontainers 연결 실패: Docker Desktop 또는 Docker Engine의 실행 상태를 확인합니다.

운영 RDS·운영 S3·Toss 웹훅의 설정과 배포 정책은 이 로컬 가이드의 범위에 포함하지 않습니다.
