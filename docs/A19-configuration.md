# A19 설정 저장과 검증

2026-09-30: PostgreSQL 설정 API에 사이트 운영 옵션과 차량 VDA 프로파일을 추가했다. 현재 검증 결과는 docs/verification.md를 따른다. 설정 저장은 통신 활성화나 실장비 시험 완료를 뜻하지 않는다. A02 추가 Mapping은 후속이며 이전 DB 시험 결과는 검증 이력으로 보존한다.

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

A16 접근 제어가 적용되어 설정 변경에는 ADMIN 권한이 필요하다. 인증 없이 쓰면 401 PRINCIPAL_REQUIRED, 권한이 부족하면 403 ROLE_ADMIN_REQUIRED를 반환한다. 읽기는 로컬 개발용으로 열려 있다.
로컬 관리자 검증용으로 `AMHS_CONFIGURATION_WRITES_ENABLED=true`와 32자 이상 임의의 `AMHS_CONFIGURATION_WRITE_TOKEN`을 지정하고 `Authorization: Bearer <token>`을 사용할 수 있다. 이 옵션은 로컬 토큰 인증을 켜며 계정 토큰 인증 전체를 끄는 옵션은 아니다. 조작 결과는 감사 이력에 기록한다. 토큰은 소스나 문서에 저장하지 않는다.

## 운영 옵션·VDA 프로파일 (V7)

- `GET /operation`, `PUT /operation?expectedVersion=N`: 사이트 단위 `{commType, nodeCount}`. commType은 PC/PLC/MQTT, nodeCount는 1~100이며 초기값은 PC/20이다. 이 선택은 어댑터 활성화가 아니다.
- `vda-profiles`는 위 리소스와 같은 조회·생성·수정 API를 제공한다. 필드는 code, interfaceName, topicVersion, manufacturer, jsonVersion, sourceAction, destAction이다. topicVersion과 jsonVersion은 독립적으로 보존한다.
- 차량은 `vdaProfileId`로 같은 사이트의 프로파일을 참조한다. PC/PLC에서는 공란이며 MQTT의 사용 중 차량은 프로파일이 필요하다. MQTT로 옵션을 바꾼 직후 기존 차량의 프로파일은 자동 생성하지 않으므로 운전 시작 전 전체 차량의 설정 재검증이 후속 필요하다.
- MQTT에서 PC/PLC로 바꾸려면 차량에 지정된 VDA 프로파일을 먼저 해제한다. 사용 중 차량은 MQTT에서 프로파일 없이 저장할 수 없으므로 먼저 사용 중지를 설정한다.
- 프로파일 수정은 MAINTENANCE에서 가능하며 참조 차량들이 변경을 공유한다. expectedVersion 충돌은 409, 중복 code는 409, RUNNING/RECOVERING 쓰기는 409다.
- 기존 connections/protocols/communications는 계속 저장되지만 새로운 사이트 운영 옵션·VDA 프로파일과의 실제 통신 해석 및 중복 설정 정리는 어댑터 연계 시 확정한다.

## 재현

### 저장된 통신 설정 대조

`GET /api/v1/sites/{siteId}/configuration/communication-check`는 사용 중 차량의 사이트 통신 방식·VDA 프로파일·기존 통신 바인딩을 읽기 전용으로 비교한다. 여러 조회는 같은 DB 스냅샷에서 수행한다. 응답의 `scope`는 `ENABLED_VEHICLE_COMMUNICATION`, `vehiclesChecked`는 대상 대수이며, `issues`는 `{vehicleId, code}` 목록이다.

- `VDA_PROFILE_REQUIRED`, `VDA_PROFILE_NOT_APPLICABLE`: 사이트 옵션과 차량 프로파일 지정의 불일치.
- `COMMUNICATION_REQUIRED`, `COMMUNICATION_AMBIGUOUS`: 통신 바인딩 누락 또는 단일 바인딩을 결정할 수 없음.
- `SITE_COMMUNICATION_MISMATCH`: 사이트 PC/PLC/MQTT와 바인딩의 연결·프로토콜 조합 불일치.
- `VDA_IDENTITY_MISMATCH`, `VDA_JSON_VERSION_MISMATCH`: 프로파일과 바인딩의 제조사/interface 또는 JSON 버전 불일치. 토픽 버전과 JSON 버전을 서로 비교하지 않는다.

`consistent=true`는 위 비교에서 불일치가 없다는 뜻이며 운전 허가는 아니다. 어댑터 미구현으로 `activationSupported`는 항상 false다. 비활성 차량은 제외한다. 이 API는 설정을 자동 보정하거나 장비에 접속하지 않으며, 어느 설정을 실제 송신에 우선할지도 결정하지 않는다. 전체 운전 준비 검증·통신 어댑터 해석·UI 연결은 후속이다.

- `scripts/server.ps1 -Test`: DB 없이 계약, 모듈 경계, 요청 차단, 그래프 검증 후 패키징.
- 별도 PostgreSQL DB `amhs_test`를 준비한 뒤 `scripts/server.ps1 -SqlTest`: 현재 Flyway migration과 실제 SQL 테스트. 기존 업무 DB를 테스트 대상으로 사용하지 않는다.
- SQL 테스트는 잘못된 조합·참조·중복·운전 모드 거부, API 저장/수정, 두 동시 수정 중 하나만 성공하는지 검사한다.
- 테스트 대부분은 롤백하며, 동시성 테스트의 커밋된 임시 포트는 finally에서 제거한다.
- 설정 편집 UI(A20)와 역할·감사(A16)는 별도 구현이며 현재 API에 연결돼 있다. 지도 편집/활성화(A04)는 후속이다.
