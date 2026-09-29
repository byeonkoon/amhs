# A19 설정 저장과 검증

2026-09-27: PostgreSQL 전환 후 별도 테스트 인스턴스에서 V1~V3 및 SQL 통합 11개 포함 전체 27개 통과. 실제 개발 서비스 연결·기존 백업 이관·A02 추가 Mapping은 미완료. 이전 SQL Server 823/OS 21 실패는 검증 이력에 보존한다.

## 소유권과 API

기본 경로: `/api/v1/sites/{siteId}/configuration`.

| 리소스 | 소유 모듈 | 내용 |
| --- | --- | --- |
| vehicles | fleet | 차량 타입, 크기, 초기/홈/충전 노드, 사용·배차 여부, 제어권, BCR·충전 정책 |
| ports | topology | revision, PortType, MakerType, AccessVehicleType, LinkedNodeId |
| connections | integration | TCP CLIENT/SERVER 또는 MQTT BROKER, host, port |
| protocols | integration | PC/PLC/VDA5050, 정확한 버전, 명시적 adapterKey |
| communications | integration | 차량과 접속/프로토콜 연결, manufacturer, 외부 식별자, VDA serial/interface |

각 리소스는 `GET /{resource}`, `GET /{resource}/{id}`, `POST /{resource}`, `PUT /{resource}/{id}?expectedVersion={version}`을 제공한다.
POST는 201, PUT은 200. 응답은 `{id, version, settings}`이며 요청 본문은 settings 자체다. 삭제 API는 없다.
수정은 version이 일치할 때만 실행되며 불일치 시 409 `VERSION_CONFLICT`를 반환한다.

차량 생성 요청 예시:
```json
{
  "revisionId": 1, "code": "OHT-NEW", "name": "신규 OHT", "vType": "OHT",
  "widthMm": 1100, "lengthMm": 1500, "heightMm": 900,
  "initialNodeId": 101, "homeNodeId": 102, "chargeNodeId": null,
  "enabled": true, "assignmentEnabled": false,
  "controlAuthority": "CONTROLLER_MANAGED", "bcrPolicy": "REQUIRED", "chargingPolicy": "DISABLED"
}
```

MQTT connection: `{"commType":"MQTT","role":"BROKER","host":"127.0.0.1","port":1883,"enabled":false}`.
VDA protocol: `{"protoType":"VDA5050","protoVersion":"3.0.0","adapterKey":"draft-vda3"}`.
Communication은 생성 응답의 ID를 vehicleId/connectionProfileId/protocolProfileId로 참조하고 manufacturer/externalVehicleId/serialNumber/interfaceName/enabled를 지정한다.
VDA 버전 초안은 2.0.0, 2.1.0, 3.0.0을 받는다. 실제 wire 어댑터 지원을 뜻하지 않는다.

## 저장 제약과 변경 잠금

- V3 migration은 fleet 특성, integration protocol/binding 테이블, version 및 같은 site/revision 복합 FK를 추가한다.
- 모든 설정 쓰기는 트랜잭션에서 site_operation 행을 SELECT FOR UPDATE로 잠근다. MAINTENANCE에서만 허용한다. RUNNING/RECOVERING은 409다.
- 후속 운전 엔진의 상태 전환도 반드시 같은 site_operation 행을 잠그고 같은 트랜잭션 규약을 따라야 한다. 현재 실제 운전 시작 API는 없다.
- 물리 연결 그래프에서 비활성 edge도 포함해 OHT/AGV 혼입을 검사한다. 포트 변경 시 fleet 소유 차량의 초기/홈/충전 노드와도 대조한다.
- MQTT는 VDA5050, TCP는 PC/PLC만 연결한다. VDA는 serial/interface 필수이며 TCP에는 둘 다 지정할 수 없다.
- 연결별 externalVehicleId와 site별 manufacturer/interface/serial의 중복을 DB에서 거부한다. VDA 식별자는 대소문자를 구별한다.
- 참조 중인 접속/프로토콜 profile의 수정은 409 PROFILE_IN_USE로 거부한다.
- 어댑터가 아직 없으므로 connection/communication enabled=true는 422 ADAPTER_NOT_IMPLEMENTED로 거부한다.
- OHT 충전 설정은 거부한다. 실제 차량 상태·반송·자원 선점은 후속 구현이다.

## 로컬 쓰기 접근

기본 실행은 모든 변경 요청에 403 CONFIGURATION_WRITES_DISABLED를 반환한다.
로컬 검증용으로 `AMHS_CONFIGURATION_WRITES_ENABLED=true`와 32자 이상 임의의 `AMHS_CONFIGURATION_WRITE_TOKEN`을 지정하고 `Authorization: Bearer <token>`을 전송해야 한다.
토큰을 소스나 문서에 저장하지 않는다. 이 임시 차단기는 사용자/역할/감사(A16)를 구현한 것이 아니다.
서버는 loopback에 바인딩하며 A16 전에는 운영용 설정 서버로 배포하지 않는다.

## 재현

- `scripts/server.ps1 -Test`: DB 없이 계약, 모듈 경계, 요청 차단, 그래프 검증 후 패키징.
- 별도 PostgreSQL DB `amhs_test`를 준비한 뒤 `scripts/server.ps1 -SqlTest`: V1~V3 Flyway와 실제 SQL 테스트. 기존 업무 DB를 테스트 대상으로 사용하지 않는다.
- SQL 테스트는 잘못된 조합·참조·중복·운전 모드 거부, API 저장/수정, 두 동시 수정 중 하나만 성공하는지 검사한다.
- 테스트 대부분은 롤백하며, 동시성 테스트의 커밋된 임시 포트는 finally에서 제거한다.
- 설정 편집 UI(A20), 지도 편집/활성화(A04), 역할·감사(A16)는 이번 API 범위에 포함하지 않는다.
