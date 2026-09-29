# 검증 기록 — 2026-09-27
## GitHub 반영 전 재검증 — 2026-09-29
- PostgreSQL 전용 테스트 인스턴스에서 전체 27개 통과, 실패/오류/skip 0. Flyway V1~V3 검증 성공.
- 서버 verify/package 및 웹 TypeScript/Vite production build 성공. PowerShell 스크립트 구문 검사 통과.
- 테스트 프로세스 종료 확인. 이번에 UI/실장비 시험은 재실행하지 않음.
- 업로드 후보의 알려진 비밀값·개인키 패턴 검사 통과. .local/.tools/백업/로그/빌드 결과 제외 확인.

## PostgreSQL 전환 결과
- 2026-09-27 PostgreSQL 18.6, 전용 55432 테스트 인스턴스에서 V1~V3 적용 성공.
- Maven verify/package: 27개 통과, 실패/오류/skip 0. SQL 통합 11개 포함.
- 사용자 설치 PostgreSQL 5432 개발 서비스와 기존 .bak 데이터 이관은 별도 미완료다.

## SQL Server 이전 실패 기록
- DB 독립 Maven verify/package 성공: 16개 통과, 실패/오류 0, SQL 테스트 11개 건너뜀.
- 새 검증: 쓰기 기본 차단·토큰 확인 3개, 비활성/역방향 edge·분리 그래프·홈/충전 노드·포트 변경 이벤트 5개.
- SQL 통합 실행은 Spring 초기 DB 연결에서 오류가 발생해 11개 테스트 실행을 완료하지 못함.
- 사용자 SQL Server 재시작 후 목록은 ONLINE이었으나, JDBC/TCP와 로컬 공유 메모리 접속에서 SQL 823 오류가 다시 발생함.
- Windows Application/MSSQLSERVER 이벤트 2026-09-27 15:52:07~08: 오류 9001, master 데이터베이스 로그 사용 불가.
- 앞선 823/OS 21은 장치가 준비되지 않았음을 보고함. 이 결과만으로 데이터 손상을 단정하지 않음. DB 삭제·복구·권한 변경은 수행하지 않음.
- V3와 신규 SQL API는 검증 미완료. A19 완료 처리하지 않음. 아래 V1/V2 결과는 이전 검증 기록임.
- 재검증: SQL Server 정상화 → `scripts/server.ps1 -SqlTest` → 개발 DB V3 적용 및 API 재확인.

## A18/A03 이전 확인 결과
- Maven package: 성공. Java 21.0.12.1, Spring Boot 3.5.16, Modulith 1.4.8.
- 계약 7건 + 모듈 경계 1건: 실패/오류 0.
- npm install로 package-lock.json 생성; TypeScript 및 Vite production build 성공.
- 실제 SQL Server AMHS_DEV에 Flyway V1/V2 적용 성공.
- health UP, 실제 지도 API node 6 / edge 8 / port 4, 초기 차량 2대.
- Playwright: 실제 API 화면 표시, OHT 차량 선택과 상세, 잘못된 site 404,
  390px 화면 수평 overflow 없음, API 503 시 오류 표시 및 오래된 지도 제거, JS 오류 없음.
- 캡처: amhs-running.png(1440px), amhs-mobile.png(390px).
DB 제약 검증은 scripts/verify-db.sql로 재현한다.

## 범위 제한
- 초기 차량 좌표는 설정 데이터다. 실제 상태 수신/시뮬레이터는 아직 없음.
- 순수 배차 제안 함수의 테스트를 실제 원자 할당 검증으로 보지 않는다.
- TCP/MQTT protocol profile 계약만 정의. 실제 어댑터 미구현.
- 코드 수준 지도 import/활성화 혼입 방지, 명령 권한, 감사, 취소/복구, 동시성·성능 시험은 후속.
- 읽기 전용 API를 loopback에 한정. 운영 배포 준비 완료가 아니다.
- 기존 Delphi 실행 및 기존 업무 DB 변경/복원은 하지 않음.
