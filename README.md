# AMHS Operations

현업에서 경험한 ACS·OCS·WMS 도메인에 대한 이해를 개인 프로젝트로 구현하는 종합 관제 시스템입니다. React + TypeScript / Java 21 + Spring Boot로 만들고 있으며 개발 DB는 **PostgreSQL**입니다. Docker와 Redis는 추후 검토합니다.

지도·차량 조회, 차량/포트/통신 설정 API, 경로 계산 및 배차 제안 계약, 반송 접수·상태 원장, 역할 권한·감사 기반이 구현돼 있습니다. 실제 반송 실행과 장비 어댑터는 후속 범위입니다. 정리된 진행 현황은 [docs/progress.md](docs/progress.md), 작업 기준은 [AGENTS.md](AGENTS.md)를 따릅니다.

## 준비 및 실행

JDK 21, Maven 3.9.9 이상, Node 22.22.0 이상, PostgreSQL 18을 준비합니다. 스크립트는 `.tools`에 로컬 도구가 있으면 사용하고 없으면 JAVA_HOME/PATH에서 찾습니다. `.tools`는 Git에 포함되지 않습니다.

Windows에서는 `./scripts/setup-tools.ps1`로 프로젝트 전용 JDK 21과 Maven 3.9.9를 준비할 수 있습니다. 공식 배포 파일의 체크섬을 확인한 뒤 `.tools`에 압축을 해제하며 전역 PATH나 서비스를 변경하지 않습니다. 최초 실행에는 인터넷이 필요합니다. Node와 PostgreSQL은 별도로 준비합니다. 다른 PC에서 이어가는 순서는 [PC 전환 안내](docs/pc-handoff.md)를 따릅니다.

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

결과는 [검증 기록](docs/verification.md), API와 임시 쓰기 제한은 [A19 설정 계약](docs/A19-configuration.md)을 확인합니다. 테스트 통과가 실제 반송·장비 연동 완료를 뜻하지 않습니다.

## 원본 DB와 PostgreSQL 전환

Flyway 활성 경로는 `server/src/main/resources/db/postgresql`입니다. `db/migration`, `infra/create-db.sql`, `scripts/verify-db.sql`은 과거 SQL Server 이력이며 PostgreSQL에서 실행하지 않습니다.

기존 현장 시스템은 SQL Server 기반이었고 이 프로젝트는 PostgreSQL로 새로 설계합니다. 기존 DB의 구조 확인이 필요한 경우 분리된 환경에서만 수행하며, 그 자료와 분석 기록은 이 저장소에 포함하지 않습니다. 여기에는 신규 스키마 설계 결정만 반영합니다.

## 다음 작업과 형상 관리

- [설계 결정 기록](docs/design-decisions.md): 각 구조를 왜 그렇게 정했는지와 현재 구현 상태.
- 기능 Inventory → 도메인 매핑 → 구현 순서를 따릅니다. 확장 예정 범위는 IO 맵핑, 스토커·버퍼 셀 관리, 상위 시스템 작업 접수, AGV/OHT 공통 Traffic입니다.
- 수정·검증이 끝난 단위로 커밋·푸시합니다. 다른 PC에서는 [PC 전환 안내](docs/pc-handoff.md)에 따라 최신 이력과 비공개 인수인계 기록을 확인합니다.
- **개발 완료 전 작업 기록과 기존 현장 시스템 참고 자료는 이 저장소에 올리지 않습니다.** 저장소 외부에서 관리하고, 여기에는 정리된 작업·진행 상황·검증 결과만 반영합니다. 원본 소스·DB 백업·로그·암호·로컬 도구·빌드 결과도 제외합니다.
