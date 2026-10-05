# AMHS Operations

현업에서 경험한 ACS·OCS·WMS 도메인에 대한 이해를 개인 프로젝트로 구현하는 종합 관제 시스템입니다. React + TypeScript / Java 21 + Spring Boot로 만들고 있으며 개발 DB는 **PostgreSQL**입니다. Docker와 Redis는 추후 검토합니다.

지도·차량 설정, Fleet 2D/3D, 반송 접수·배차·경로·Traffic, 내부 AGV/OHT·설비 시뮬레이션, 재고 연계, 취소·복구 및 역할 권한·감사 기반을 구현했습니다. 실장비 통신 어댑터와 실제 연결·현장 검증은 후속 범위입니다. 구현 범위는 [진행 현황](docs/progress.md)과 [설계 결정](docs/design-decisions.md)에 정리합니다.

## 준비 및 실행

JDK 21, Maven 3.9.9 이상, Node 22.22.0 이상, PostgreSQL 18을 준비합니다. 스크립트는 `.tools`에 로컬 도구가 있으면 사용하고 없으면 JAVA_HOME/PATH에서 찾습니다. `.tools`는 Git에 포함되지 않습니다.

Windows에서는 `./scripts/setup-tools.ps1`로 프로젝트 전용 JDK 21과 Maven 3.9.9를 준비할 수 있습니다. 공식 배포 파일의 체크섬을 확인한 뒤 `.tools`에 압축을 해제하며 전역 PATH나 서비스를 변경하지 않습니다. 최초 실행에는 인터넷이 필요합니다. Node와 PostgreSQL은 별도로 준비합니다.

새 로컬 PostgreSQL에 전용 계정과 개발/테스트 DB를 생성합니다. postgres 비밀번호는 화면에 표시되지 않는 입력 창에 입력합니다.

```powershell
.\scripts\setup-postgres.ps1
.\scripts\server.ps1 -Test
.\scripts\server.ps1
```

기존 계정은 `scripts/configure-postgres.ps1`의 DbUser/DevDatabase/TestDatabase 옵션으로 지정합니다. Windows DPAPI로 암호화한 연결 정보는 `.local`에만 저장하며 다른 PC에서 다시 설정해야 합니다.

별도 터미널:

```powershell
cd web
npm ci
cd ..
.\scripts\web.ps1
```

- 화면: http://127.0.0.1:5173
- 서버: http://127.0.0.1:8080/actuator/health
- 지도: `/api/v1/sites/1/maps/1`, 초기 차량: `/api/v1/sites/1/vehicles`

위 주소는 기동 후 사용할 수 있습니다. 두 서버는 loopback에 바인딩합니다. 초기 차량 위치는 설정 데이터이며 실제 상태가 아닙니다 (`INITIAL_CONFIGURATION`, `NOT_CONNECTED`).

스크립트 없이도 `mvn -f server/pom.xml verify`, `java -jar server/target/amhs-server-0.1.0.jar`, web 폴더의 `npm run dev`로 실행할 수 있습니다. 연결 환경변수는 AMHS_DB_URL/AMHS_DB_USER/AMHS_DB_PASSWORD입니다. 기본 URL은 `jdbc:postgresql://127.0.0.1:5432/amhs_dev`입니다.

## 검증

- `scripts/server.ps1 -Test`: DB 독립 시험만 실행하며 SQL 통합 시험은 skip합니다.
- `scripts/server.ps1 -SqlTest`: 별도 `amhs_test`에서 SQL 통합 시험도 실행합니다. AMHS_TEST_DB_URL로 테스트 DB만 지정합니다.
- `scripts/test-postgres.ps1`: 설치된 PostgreSQL 실행 파일로 격리된 55432 인스턴스에서 전체 테스트 후 종료합니다. 기존 5432 서비스는 변경하지 않습니다.
- web 폴더의 `npm run build`: TypeScript + Vite production build.
- `scripts/verify-postgres.sql`: psql에서 migration/seed/FK/CHECK 확인.
- `scripts/verify-ui.cjs`: Playwright/Edge로 실행 중인 앱의 지도·선택·모바일·오류 표시 확인. AMHS_RUNTIME_MODULES에 playwright 설치 node_modules 경로를 지정합니다.

결과는 [검증 기록](docs/verification.md), API와 임시 쓰기 제한은 [A19 설정 계약](docs/A19-configuration.md)을 확인합니다. 테스트 통과가 실장비 반송·장비 연동 완료를 뜻하지 않습니다.

## 데이터베이스 구성

Flyway 활성 경로는 `server/src/main/resources/db/postgresql`입니다. `db/migration`, `infra/create-db.sql`, `scripts/verify-db.sql`은 과거 SQL Server 이력이며 PostgreSQL에서 실행하지 않습니다.

반송·차량·설비·재고 도메인의 책임과 정합성 요구사항에 맞춰 PostgreSQL 스키마를 설계하고, Flyway로 변경 이력을 관리합니다.

## 설계 및 공개 범위

- [설계 결정 기록](docs/design-decisions.md): 각 구조를 왜 그렇게 정했는지와 현재 구현 상태.
- [AMHS 공개 설계 개요](docs/portfolio-amhs-design.md): 개인 포트폴리오용으로 정리한 아키텍처와 설계 의도. [검증 시나리오](docs/portfolio-amhs-validation.md) 및 개념도 포함.
- 운영 요구사항 → 도메인 계약 → 구현·검증 순서로 구체화합니다. 확장 범위는 실장비 Adapter, 상위 시스템별 연계 및 부하·장애 시나리오 검증입니다.
- 구현 완료, 설계 확정, 후속 구현을 문서에서 구분합니다. 시뮬레이션 검증은 실제 장비 통신 및 현장 검증과 별개입니다.
- 저장소에는 애플리케이션 소스, 설계 의도, 구현 범위와 검증 결과를 제공합니다. 고객·설비 식별 정보, 운영 데이터·백업·로그, 인증정보, 로컬 도구와 빌드 결과는 포함하지 않습니다.
