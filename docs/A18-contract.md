# A18 차량·포트 및 배차 계약
기준: Notion 02·03·04의 2026-09-23 합의. 작성 2026-09-27.
이 문서는 신규 계약 정의다. 기존 현장 시스템의 통신 계약과 혼동하지 않는다.

## 소유권과 공개 API
- platform: Site와 공통 VehicleType(OHT/AGV). 공통 설비 식별은 추후 platform 소유로 단일화한다.
- topology: MapRevision, Node, Edge, Port, ConflictResource 정의. 공개 TopologyQuery.getMap(siteId, revisionId). 포트 실행 점유는 소유하지 않는다.
- fleet: VehicleProfile, enable/assignmentEnable, 크기·초기/Home/Charge 노드 참조·BCR 정책 및 최신 차량 상태. 현재 구현은 설정 조회 부분뿐이다.
- integration: 차량별 CommType(TCP/MQTT), ProtoType(PC/PLC/VDA5050), ProtoVersion, Manufacturer, ProtocolProfileId, ConnectionProfileId. TCP는 차량 endpoint, MQTT는 broker endpoint. VDA는 SerialNumber와 InterfaceName 필수. 실제 어댑터가 등록된 조합만 활성화한다. CommunicationProfile은 입력 계약이며 지원 어댑터의 증거가 아니다.
- routing: RoutePlanner.find는 경로/비용 또는 empty를 반환한다. 다른 모듈 상태를 변경하지 않는다.
- dispatch: DispatchEvaluator.evaluate는 후보 제안 또는 사유를 반환한다. 할당 원장은 변경하지 않는다.
- transport(후속): 작업·할당·Carrier 위치·목적지 slot 선점 원장. 공개 createOrder/cancelOrder.
- traffic(후속): 예약/점유 원장. 공개 reserve/confirmEntry/confirmExit. 경로 계산만으로 이동을 허가하지 않는다.
- alarm/realtime(후속): 알람 원장 / 확정된 snapshot·delta 전달.
모듈 루트의 public 타입이 공개 API다. 하위 infrastructure/api는 모듈 내부이며 Modulith.verify로 경계를 검사한다.
의존성: dispatch → routing/topology/platform, routing → topology, topology/fleet → platform. infrastructure 역참조는 금지한다.

## 식별·포트·지도
내부 ID bigint, 외부 코드 NVARCHAR(64), 좌표 mm, 시간 UTC.
PortType·MakerType은 원본 의미를 유지할 문자열이다. 가상 seed의 LOAD/UNLOAD/SYNTHETIC은 원본 코드 매핑이 아니다.
AccessVehicleType은 OHT 또는 AGV, LinkedNodeId는 같은 site/revision의 노드를 참조한다.
OHT와 AGV는 단절된 실제 Node/Edge 그래프와 포트 접근 노드로 분리한다. layer는 필수가 아니다.
지도 활성화 검증 시 반대 차종 포트가 동일 연결 성분에 섞이는 구성을 거부한다. 현재 편집/import/활성화 API는 미구현이다.
지도 revision은 작업 생성 시 고정하고 진행 중 변경하지 않는다.
FK는 site/revision을 포함하여 노드·edge·port·차량 초기 노드의 교차 참조를 막는다.

## 배차 결정과 원자 할당
1. source/destination이 해당 site/revision에 존재하고 서로 다름을 검사.
2. 양쪽 AccessVehicleType 일치 검사. 불일치는 PORT_TYPE_MISMATCH.
3. 같은 VType, enabled, assignmentEnabled, online, remote, idle, 유효한 최신 수신 상태의 후보만 평가.
4. 각 후보의 현재 위치→출발지 및 출발지→목적지 유향 경로를 모두 계산. 실패한 후보는 제외하고 계속 평가.
5. 두 경로 비용 합 최소, 동률이면 차량 ID 오름차순. 작업 간 우선순위 높은 순, 같은 우선순위는 생성 시각·ID 순으로 처리(후속).
6. 제안은 예약이나 이동 허가가 아니다. 사이트 단일 작성자 안에서 DB 트랜잭션을 시작하고 지도 revision·차량 최신 상태·작업 상태·Carrier 위치·목적지 slot 가용성을 재검증.
7. vehicle→carrier→destination slot의 정렬된 자원 키 순으로 잠금. 활성 assignment/claim에 released_at IS NULL 필터 UNIQUE 제약을 적용. 충돌은 전체 rollback 후 재평가.
8. 작업 전이+assignment+claim+event+command/outbox를 같은 트랜잭션으로 commit. 네트워크 송신은 commit 후.
9. 송신 성공/ENQUEUED는 완료가 아니다. 장비 완료 증거를 받아 다음 전이를 확정한다.
6~9는 A06/A10/A11/A21/A22 구현 계약이다. 현재 순수 평가 테스트 통과를 원자 할당 구현 완료로 기록하지 않는다.

## HTTP 계약
GET /api/v1/sites/{siteId}/maps/{revisionId}: 200 지도 / 404 MAP_NOT_FOUND.
GET /api/v1/sites/{siteId}/vehicles: 200 설정 목록. currentNodeId는 현재 단계에서는 initial_node_id이고 positionSource=INITIAL_CONFIGURATION, state=NOT_CONNECTED.
GET /actuator/health: 실제 DB health 포함.
후속 POST /api/v1/sites/{siteId}/transport-orders:
Idempotency-Key와 requestSource, sourcePortId, destinationPortId, carrierId, priority(0..100) 필수.
site+requestSource+key 동일 및 payload 동일은 기존 작업 반환, payload 상이 409.
포트 불일치/잘못된 입력 422. 가용 차량 없음은 QUEUED 대기(NO_AVAILABLE_VEHICLE). 정적 경로 없음과 일시 차단은 분리(NO_STATIC_ROUTE / TEMPORARILY_BLOCKED).
모든 오류는 code,message,traceId 규격으로 A06에서 통일. 현재 조회 API는 Spring 기본 오류 응답이다.
변경 API는 A16 서버 권한/감사 완료 전 외부에 노출하지 않는다. 이번 서버는 loopback 한정 조회 전용이다.

## 수용 시험
- 더 가까운 AGV가 있어도 OHT 포트 작업에는 OHT 후보만 제안.
- 한 OHT 후보가 고립되어도 다른 OHT를 평가.
- 출발·도착 차종 불일치 거부.
- 두 단절 그래프 간 경로 없음 / 역방향 edge가 없으면 역방향 경로 없음.
- offline 후보 제외, 비용 동률 결정 순서, 음수 비용 거부, VDA 식별 누락 거부.
- 후속 통합 시험: 동일 차량·Carrier·slot의 동시 선점 각각 한 건만 성공, stale 수신 제외, 재검증 실패 rollback.
