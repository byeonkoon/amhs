# A18 차량·포트 및 배차 계약
설계 계약 정리. 작성 2026-09-27, 현재 구현 대조 갱신 2026-10-01.
이 문서는 본 프로젝트의 공통 명령·응답 계약을 정의한다. 실장비 통신 규격과의 변환은 Adapter의 책임이다.
각 항목은 **계약**(지켜야 할 규칙), **현재 구현**, **미구현**을 구분한다. 계약을 구현 완료로 읽지 않는다.

## 소유권과 공개 API
- platform: Site와 공통 VehicleType(OHT/AGV), 사이트 운영 옵션(통신 방식, 허가 노드 수, 연장 판정, 상태 신선도 등). 공통 설비 식별은 추후 platform 소유로 단일화한다.
- topology: MapRevision, Node(정지 노드·합류 지점 속성 포함), Edge, Port, Zone 정의. 공개 TopologyQuery.getMap(siteId, revisionId). 포트 실행 점유는 소유하지 않는다.
- fleet: VehicleProfile, enable/assignmentEnable, 크기·초기/Home/Charge 노드 참조·BCR 정책. **현재 구현**: 설정 조회와 최근 보고 상태(위치·온라인·원격·정지·화물 유무·출처·보고 시각·목적지 변경 가능 여부).
- integration: **현재 구현**: 통신 방식(PC/PLC/MQTT)은 사이트 운영 옵션에서 정하고, MQTT 차량은 이름 있는 VDA 프로파일 하나를 지정한다(A19). 장비 명령 아웃박스와 게이트웨이. 실제 어댑터가 등록된 조합만 활성화하며 현재 등록된 어댑터는 없다. 기존 차량별 접속·프로토콜 바인딩과 새 구조의 정리는 **미구현**.
- routing: RoutePlanner.find는 경로/비용 또는 사유를 반환한다. 다른 모듈 상태를 변경하지 않는다.
- dispatch: DispatchEvaluator.evaluate는 후보 제안 또는 사유를 반환한다. 할당 원장은 변경하지 않는다.
- transport: **현재 구현**: 작업 접수·상태 머신·할당·경로 저장·이동 시작/연장·취소·복구 종료, 작업을 마치고 Zone에 남은 차량의 퇴출 이동(작업 없는 이동). Carrier 위치는 복구 종료 때 사람이 확인한 값으로만 기록한다. 목적지 slot 선점은 **미구현**.
- traffic: **현재 구현**: 노드 점유 원장, 연속 구간 허가(request), 도착 보고에 따른 반납(advanced), 작업 종료 시 앞쪽 반납(releaseAhead), 대기 기록과 교착 탐지, Zone 정원, 합류 순서. 경로 계산만으로 이동을 허가하지 않는다.
- simulation: **현재 구현**: 설정으로만 켜지는 서버 내장 시뮬레이터(공통 명령·증거 계약 사용). 실제 규격 시뮬레이터는 미구현.
- alarm/realtime: **미구현**. 알람 원장 / 확정된 snapshot·delta 전달.
모듈 루트의 public 타입이 공개 API다. 하위 infrastructure/api는 모듈 내부이며 Modulith.verify로 경계를 검사한다.
의존성: dispatch → routing/topology/platform, routing → topology, topology/fleet → platform. infrastructure 역참조는 금지한다.

## 식별·포트·지도
내부 ID bigint, 외부 코드 문자열(64), 좌표 mm, 시간 UTC.
PortType은 이 프로젝트에서 정의한 문자열이다. 가상 seed의 LOAD·UNLOAD·SYNTHETIC은 외부 시스템 코드 매핑이 아니다. 장비 제조사를 식별하는 칸은 두지 않는다.
AccessVehicleType은 OHT 또는 AGV, LinkedNodeId는 같은 site/revision의 노드를 참조한다.
OHT와 AGV는 단절된 실제 Node/Edge 그래프와 포트 접근 노드로 분리한다. layer는 필수가 아니다.
지도 활성화 검증 시 반대 차종 포트가 동일 연결 성분에 섞이는 구성을 거부한다. 노드·구간 파라미터 편집은 구현했고, 지도 import/활성화 API는 **미구현**이다.
지도 revision은 작업 생성 시 고정하고 진행 중 변경하지 않는다.
FK는 site/revision을 포함하여 노드·edge·port·차량 초기 노드의 교차 참조를 막는다.

## 배차 결정과 원자 할당
1. source/destination이 해당 site/revision에 존재하고 서로 다름을 검사.
2. 양쪽 AccessVehicleType 일치 검사. 불일치는 ACCESS_VEHICLE_TYPE_MISMATCH.
3. 같은 VType, enabled, assignmentEnabled, online, remote, idle이고 **최근 보고가 사이트의 상태 신선도 기준 안에 있는** 후보만 평가한다. 설정값만 있고 보고가 없는 차량은 후보가 아니다. 남은 후보가 모두 오래된 상태뿐이면 VEHICLE_STATE_STALE.
4. 각 후보의 현재 위치→출발지 및 출발지→목적지 유향 경로를 모두 계산. 실패한 후보는 제외하고 계속 평가.
5. 두 경로 비용 합 최소, 동률이면 차량 ID 오름차순. 작업 간 우선순위 처리는 **미구현**.
6. 제안은 예약이나 이동 허가가 아니다. 트랜잭션 안에서 차량을 잠근 뒤 자격·상태 신선도를 다시 확인하고, 경로를 계산한 위치에서 차량이 움직였으면 VEHICLE_MOVED로 거부한다. Carrier 위치·목적지 slot 재검증은 **미구현**.
7. 차량 잠금은 ID 오름차순. 활성 assignment에 released_at IS NULL 필터 UNIQUE 제약을 적용하고 충돌은 전체 rollback. carrier·slot 잠금은 **미구현**.
8. 작업 전이+assignment+event+장비 명령을 같은 트랜잭션으로 commit. 송신은 commit 후 아웃박스에서 한다.
9. **단계별 이동 허가.** 경로는 접근·반송 구간을 합쳐 저장하지만, 적재 시작 전에는 **출발지까지만** 허가한다. 반송 구간은 적재 완료가 보고된 뒤 새 이동 명령으로 준다. 적재·하역·취소 요청·복구 대기 단계에서는 이동이나 연장을 만들지 않는다.
10. **취소 원자성.** 취소는 작업의 명령 행을 송신기와 같은 잠금으로 잡고 판단한다. 아직 나가지 않은 명령은 철회하고 작업을 끝낸다. 이미 나갔거나 나갔을 수 있는 명령이 있으면 차량이 움직이는 중일 수 있으므로 취소 요청 상태로 두고 할당과 점유를 유지하며, 정지 확인 뒤에 끝낸다.
11. **차량별 명령 순서.** 장비 명령은 사이트와 차량에 속하며 작업이 없을 수 있다(Zone 퇴출). 한 차량의 명령은 기록된 순서대로 하나씩 나간다. 앞선 명령이 대기·재시도 대기·결과 불명이면 뒤 명령을 보내지 않는다. 퇴출 중인 차량은 배차하지 않는다.
12. 송신 성공은 완료가 아니다. 적재·하역 완료는 화물 위치를 정하는 증거(센서 전이·동작 완료 보고 등)가 있어야 확정되며, 화물 위치는 같은 트랜잭션에서 바뀐다. 이적재 미수행이 증명되면 상한까지 재시도한다. 장비 보고를 증거로 바꾸는 어댑터는 **미구현**.

## HTTP 계약
GET /api/v1/sites/{siteId}/maps/{revisionId}: 200 지도 / 404.
GET /api/v1/sites/{siteId}/vehicles: 200 설정 목록. 실시간 상태는 GET /api/v1/sites/{siteId}/maps/{revisionId}/vehicle-state.
GET /actuator/health: 실제 DB health 포함.
POST /api/v1/sites/{siteId}/transport-orders: Idempotency-Key(헤더 또는 본문), X-Amhs-Request-Source, 본문 sourcePortCode·destinationPortCode·carrierCode·priority(0..100).
- site+requestSource+key와 내용이 같으면 기존 작업 반환(200), 내용이 다르면 409. 새 작업은 201, 상태 QUEUED.
- 포트 불일치·잘못된 입력은 422. 가용 차량 없음은 배차 시점의 재시도 가능한 사유이며 접수 거부가 아니다.
POST …/transport-orders/{id}/assignment, …/cancel, …/recovery(본문 carrierLocation SOURCE|DESTINATION, reason 필수), …/transitions(본문 status, reason, evidence{kind, commandId, carrierLocation}), POST …/movement-authority, …/operation-sweep. 반송 이력의 조작 주체는 접근 게이트를 통과한 실제 계정이다.
반송 API 오류는 code·message·traceId로 응답한다. 변경 요청은 접근 게이트를 거치며 운영 명령은 운전자 이상, 설정 변경은 영역별 권한을 요구한다(A16).

## 수용 시험
- 더 가까운 AGV가 있어도 OHT 포트 작업에는 OHT 후보만 제안. (구현·시험)
- 한 OHT 후보가 고립되어도 다른 OHT를 평가. (구현·시험)
- 출발·도착 차종 불일치 거부. (구현·시험)
- 두 단절 그래프 간 경로 없음 / 역방향 edge가 없으면 역방향 경로 없음. (구현·시험)
- offline 후보 제외, 비용 동률 결정 순서, 음수 비용 거부. (구현·시험)
- 동일 차량 동시 선점은 한 건만 성공. (구현·시험) Carrier·slot 동시 선점은 **미구현**.
- 오래된 상태 보고로는 배차·이동·연장하지 않는다. (구현·시험)
- 첫 이동 허가는 출발지에서 끝나고 반송 구간은 적재 보고 뒤에 준다. (구현·시험)
- 나가지 않은 명령만 있으면 취소가 즉시 끝나고 명령은 철회된다. 나간 명령이 있으면 취소 요청으로 남고 점유를 유지한다. (구현·시험) 송신 중 동시 취소의 실제 경합 실행은 **미검증**.
