# AMHS Operations

현업에서 경험한 ACS·OCS·WMS 도메인에 대한 이해를 개인 프로젝트로 구현하는 종합 관제 시스템입니다. React + TypeScript / Java 21 + Spring Boot로 만들고 있으며 개발 DB는 **PostgreSQL**입니다. Docker와 Redis는 추후 검토합니다.

지도·차량 설정, Fleet 2D/3D, 반송 접수·배차·경로·Traffic, 내부 AGV/OHT·설비 시뮬레이션, 재고 연계, 취소·복구 및 역할 권한·감사 기반을 구현했습니다.

여기에 **바깥과 주고받는 길**을 더했습니다. 차량 링크는 TCP 양방향(JSON 한 줄·바이트 프레임)과 MQTT, PLC 워드 블록(Modbus·MELSEC)을 쓰고, 어느 말로 적는지는 **코드가 아니라 매핑 문서**가 정합니다. 설비는 상태를 읽고 포트 예고 한 가지를 씁니다. 상위 시스템에는 작업이 어떻게 끝났는지와(원하면) 진행 상황을 알리며, 그 모양도 매핑 문서가 정합니다. 링크는 TLS로 감싸고 반대편이 누구인지 인증서로 확인하며, 떼어낸 게이트웨이는 폐기 목록으로 막습니다.

송신 판정은 어디서나 **세 가지**입니다 — 쓰지 못함(다시 보내도 됨), 수신 확인(수행이 아님), 썼는데 아무도 모름(스스로 다시 보내지 않음). 그 셋을 고장을 주입해 확인했고, 현장 규모(노드 321·포트 64·차량 25대)에서 부하를 걸어 불변식을 확인했습니다([검증 시나리오](docs/portfolio-amhs-validation.md)).

**실장비·실제 상위 시스템과의 연결과 현장 검증은 후속 범위입니다.** 지금까지 상대는 모두 시험용 소켓이나 시험 안의 HTTP 서버입니다. 구현 범위는 [진행 현황](docs/progress.md)과 [설계 결정](docs/design-decisions.md)에 정리합니다.

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
- `scripts/load-run.ps1`: 격리 클러스터에 **전용 DB를 만들어** 서버를 띄우고, 지도를 API로 올려 적용하고, 작업을 흘리며 보고를 찍고, 끝나고 `scripts/load-invariants.sql`로 바닥에 남은 것을 묻습니다. 다른 응용이 그 DB에 붙어 있으면 거부하고, 측정 중 사이트가 진행을 멈추면 그 즉시 중단해 보고서에 적습니다.

```powershell
.\scripts\load-run.ps1 -Minutes 15 -PerMinute 240 -PerVehicle 2 -Map docs/sample-maps/large-site.json -User <Lv4 계정> -Password <비밀번호>
```

결과는 [검증 기록](docs/verification.md), API와 임시 쓰기 제한은 [A19 설정 계약](docs/A19-configuration.md)을 확인합니다. **SQL 시험은 `AMHS_SQL_TEST=true` 없이 조용히 skip되므로 통과 여부는 skip 수까지 봅니다.** 테스트 통과가 실장비 반송·장비 연동 완료를 뜻하지 않습니다.

## 데이터베이스 구성

Flyway 활성 경로는 `server/src/main/resources/db/postgresql`입니다. `db/migration`, `infra/create-db.sql`, `scripts/verify-db.sql`은 과거 SQL Server 이력이며 PostgreSQL에서 실행하지 않습니다.

반송·차량·설비·재고 도메인의 책임과 정합성 요구사항에 맞춰 PostgreSQL 스키마를 설계하고, Flyway로 변경 이력을 관리합니다.

## 설계 및 공개 범위

- [설계 결정 기록](docs/design-decisions.md): 각 구조를 왜 그렇게 정했는지와 현재 구현 상태.
- [AMHS 공개 설계 개요](docs/portfolio-amhs-design.md): 개인 포트폴리오용으로 정리한 아키텍처와 설계 의도. [검증 시나리오](docs/portfolio-amhs-validation.md) 및 개념도 포함.
- 계약 문서: [차량 TCP](docs/vehicle-tcp-contract.md) · [매핑 파일](docs/mapping-file.md) · [설비 OPC UA](docs/equipment-opcua-contract.md) · [상위 통보](docs/host-notice-contract.md) · [링크 보안](docs/link-security.md) · [지도 파일](docs/map-file.md).
- 운영 요구사항 → 도메인 계약 → 구현·검증 순서로 구체화합니다. 남은 확장 범위는 실장비 Adapter와 실제 상위 시스템 연결, 더 긴 시간·더 큰 규모의 부하 자동 회귀입니다.
- 구현 완료, 설계 확정, 후속 구현을 문서에서 구분합니다. 시뮬레이션 검증은 실제 장비 통신 및 현장 검증과 별개입니다.
- 저장소에는 애플리케이션 소스, 설계 의도, 구현 범위와 검증 결과를 제공합니다. 고객·설비 식별 정보, 운영 데이터·백업·로그, 인증정보, 로컬 도구와 빌드 결과는 포함하지 않습니다.
