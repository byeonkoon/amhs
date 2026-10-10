# 차량 메시지 매핑 파일

차량마다 말이 다릅니다. "이 노드들로 가라"가 제조사마다 다른 JSON 모양이고, "원격 제어 중"이라는 뜻의
필드 이름도 제각각입니다. 제조사마다 어댑터를 새로 쓰면 **현장의 배선이 저장소로 들어오고 차량 한 대가
늘 때마다 배포가 필요**합니다. 그래서 이 시스템은 번역을 **코드가 아니라 문서**로 둡니다.

- 판단(무엇을 보낼지, 언제 보낼지)은 저장소에 남습니다.
- 방언(어떤 이름으로, 어떤 모양으로)은 현장의 매핑 문서에 남습니다.

## 설계 원칙

**매핑은 언어가 아닙니다.** 평가할 수식도, 실행할 스크립트도 없습니다. 값은 정해진 자리에서 가져와,
표를 거쳐 번역하고, 이 관제가 이미 아는 이름으로 적습니다. 그래서

- 문서를 읽는 사람이 **무슨 일이 일어날지 전부 예측**할 수 있습니다.
- 매핑으로 표현할 수 없는 것은 어댑터가 자라야 할 지점이고, 그건 시험과 함께 공개된 코드에 들어갑니다.
- 매핑 문서에 현장 고유값(토픽 접두사·슬롯 번호·제조사 이름)이 들어가므로 **저장소에는 엔진만** 남습니다.

## 문서 전체 모양

```json
{
  "mapping": "VDA5050-V3",
  "adapter": "MQTT_JSON_V1",
  "version": 1,
  "notes": "사람이 읽을 설명",

  "identity": { "fromTopic": "serial" },

  "outbound": {
    "MOVE": { "topic": "...", "body": { "필드": 규칙, ... } }
  },

  "inbound": [
    { "when": { "topic": "..." }, "report": { "필드": 규칙, ... } }
  ]
}
```

| 키 | 뜻 |
|---|---|
| `mapping` | 이 문서의 이름. 프로토콜 프로파일이 이 이름으로 가리킵니다 |
| `adapter` | 이 문서를 읽는 어댑터 (`MQTT_JSON_V1`, `JSON_LINE_V1` …) |
| `identity` | 메시지가 **어느 차량의 것인지** 아는 방법. `fromTopic`(토픽에서 잡은 조각 이름) 또는 `fromField`(본문 경로) |
| `outbound` | 명령 종류별로 **어디로 보내고 무엇을 싣는지** |
| `inbound` | 들어온 메시지를 **어떻게 알아보고 무엇으로 읽는지** |

## 값 규칙 (Rule)

모든 필드는 같은 모양의 규칙입니다.

```json
{ "from": "...", "values": {...}, "op": "...", "default": ..., "each": {...} }
```

적용 순서가 문서의 약속입니다: **가져오기 → 표로 번역 → 변환 → 없으면 기본값**.

### `from` — 어디서 가져오는가 (닫힌 목록)

| 접두사 | 의미 | 예 |
|---|---|---|
| `command.` | 명령 자체 | `command.commandId` `command.commandType` `command.orderId` `command.vehicleId` `command.externalId` `command.sequence` `command.sentAt` |
| `payload.` | 결정이 만든 내용 | `payload.authorizedNodeIds` `payload.target.nodeId` |
| `body.` | 들어온 메시지 본문 | `body.batteryState.batteryCharge` `body.loads[0].loadId` |
| `topic.` | 토픽에서 잡은 조각 | `topic.manufacturer` |
| `item` · `index` | `each` 안에서 원소와 순번 | `item` `index` |

경로는 점으로 내려가고 `이름[0]`으로 목록 원소를 집습니다. **없으면 null**이며, 빈 값으로 바뀌지 않습니다.

### `constant` — 고정값

객체가 아닌 값을 바로 쓰면 고정값입니다. 아래 둘은 같습니다.

```json
"version": "3.0.0"
"version": { "constant": "3.0.0" }
```

### `values` — 표로 번역

제조사의 말을 이 관제의 말로 바꿉니다.

```json
"mode": { "from": "body.operatingMode",
          "values": { "AUTOMATIC": "REMOTE", "SEMIAUTOMATIC": "SEMI_AUTO", "MANUAL": "MANUAL" } }
```

> **표에 없는 값은 통과하지 않습니다.** 결과는 null이 되고 그 항목은 보고에서 빠집니다.
> 목록에 없는 상태는 이 관제가 **대응할 수 없는 상태**이고, 그걸 아는 값으로 추측하면 처음 보는 상태의
> 차량에 작업을 주게 됩니다. 새 값이 생기면 표에 적는 것이 맞습니다.

### `op` — 변환 (닫힌 목록)

`int` · `number` · `text` · `bool` · `negate` · `count` · `notEmpty` · `first` · `last`

```json
"carrier": { "from": "body.loads", "op": "notEmpty" }
```

`count`와 `notEmpty`만 값이 없을 때도 답합니다(0, false). 나머지는 값이 없으면 null입니다.

### `default` — 없을 때만

가져온 결과가 없을 때만 쓰입니다. 값이 있는데 덮어쓰지 않습니다.

### `each` — 목록을 원소마다

```json
"nodes": {
  "from": "payload.authorizedNodeIds",
  "each": {
    "nodeId":     { "from": "item", "op": "text" },
    "sequenceId": { "from": "index" },
    "released":   true
  }
}
```

## 토픽 패턴

`{이름}`은 한 레벨을 잡아 `topic.이름`으로 쓸 수 있게 하고, MQTT의 `+`(한 레벨)·`#`(나머지)도 그대로
씁니다. 구독 문자열과 같은 모양으로 읽히도록 한 것입니다.

```
{prefix}/{version}/{manufacturer}/{serial}/state
uagv/+/ACME/{serial}/state
uagv/v3/#
```

보낼 때는 `{이름}`이 그 차량의 신원 값(연결·통신·VDA 프로파일에서 옵니다)으로 채워집니다.

## 보고 항목 (닫힌 목록)

`inbound`의 `report`가 만들 수 있는 이름은 **이 관제가 실제로 행동하는 항목뿐**입니다.

| 항목 | 뜻 |
|---|---|
| `nodeId` · `nodeCode` | 지금 서 있는 노드. 숫자 id 또는 지도상의 코드 |
| `mode` | `MANUAL` · `SEMI_AUTO` · `REMOTE` |
| `status` | `IDLE` · `MOVING` · `ERROR` · `PAUSED` · `CHARGING` · `LOADING` · `UNLOADING` · `RESET` |
| `carrier` | 화물을 싣고 있는가 |
| `batteryPct` | 충전 상태(%) |
| `destinationChangeEnabled` | 지금 목적지 변경을 받을 수 있는가 |
| `online` | 링크가 살아 있는가 (연결 채널 메시지용) |

다른 이름을 쓰면 **문서를 읽을 때 거절**합니다. 아무도 읽지 않는 항목을 적어 두고 반영됐다고 믿는 일을
막기 위해서입니다.

## 잘못된 문서

문서는 사람이 손으로 고칩니다. 그래서 **잘못된 곳을 한 번에 전부** 말합니다.

```
mapping: 이름이 없습니다;
identity: 메시지가 어느 차량의 것인지 알 길이 없습니다;
outbound.MOVE: topic이 없습니다;
outbound.MOVE.a: from은 [command, payload, body, topic, item, index] 가운데 하나로 시작해야 합니다 (nowhere.x);
inbound[0].speed: 이 관제가 아는 보고 항목이 아닙니다 [...]
```

## 예제

[sample-mappings/vda5050-v3.json](sample-mappings/vda5050-v3.json) — VDA5050 3.0 최소 반송 계약
(order·instantActions 발행, state·connection 수신).

## 아직 아닌 것

- 매핑 문서를 올리고 보관하는 저장소·화면(지금은 파일). 지도 JSON과 같은 방식으로 올리도록 맞출 예정입니다.
- PLC 슬롯·오프셋을 위한 `slot` 대상(지금 대상은 JSON 경로뿐).
- 이적재 완료 같은 **작업 증거**의 매핑. 작업 상태를 바꾸는 판단은 링크가 아니라 작업 원장의 몫이므로
  별도 계약(`POST /transport-orders/{id}/transitions`)으로 둡니다.
