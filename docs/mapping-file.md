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

## 자리로 찾는 어댑터 — `layout`

JSON은 이름으로 찾지만 **PC 프레임과 PLC 레지스터는 자리로 찾습니다.** 그래서 "어떤 이름이 어디에 있고
무슨 타입인가"를 `layout`에 한 번 적고, `outbound`/`inbound`는 **이름으로만** 값을 채웁니다. 차량사가
오프셋을 바꾸면 `layout`만 고칩니다.

```json
"layout": {
  "command":  { "at": 1,  "type": "u8" },
  "vehicle":  { "at": 4,  "type": "ascii", "length": 8 },
  "nodes":    { "at": 13, "type": "u16", "repeat": 6 },
  "battery":  { "at": 28, "type": "u16", "scale": 0.1 },

  "sequence": { "word": 1, "type": "u16" },
  "request":  { "word": 10, "bit": 0 },
  "nodeNow":  { "word": 3, "type": "u16", "area": "read" }
}
```

| 키 | 뜻 |
|---|---|
| `at` | 바이트 오프셋 |
| `word` | 16비트 워드 번호. **워드 n은 바이트 2n**이라, 프레임과 레지스터를 한 어휘로 적습니다 |
| `type` | `u8` `u16` `u32` `i8` `i16` `i32` `bcd` `ascii` `bit` |
| `bit` | 비트 번호. `at`과 쓰면 **그 바이트의 비트**, `word`와 쓰면 **그 워드의 비트**(0이 최하위, 바이트 순서를 따라갑니다) |
| `length` | `ascii`·`bcd`의 바이트 수 |
| `repeat` | 같은 타입이 연속으로 몇 개 (목록이 됩니다) |
| `scale` | 정수를 실수로 (`615` × `0.1` = `61.5`) |
| `area` | PLC에서 `write`(기본) 블록인지 `read` 블록인지 |

**값이 자리에 안 들어가면 보내지 않고 거절합니다.** 잘린 노드 번호는 다른 노드이고, 1바이트 모자란
프레임은 "거의 맞는" 프레임이 아닙니다.

상수는 문서가 쓰는 대로 **16진수 문자열**로 적어도 됩니다 — `"command": "0x10"`.

## PC 프레임 (`PC_FRAME_V1`)

```json
"frame": {
  "size": 32, "start": "0x02", "end": "0x03", "endian": "big",
  "checksum": { "type": "xor", "at": 30, "over": [1, 29] }
}
```

- 고정 길이 프레임을 **끝까지 다 읽고 나서** 해석합니다. 중간까지 읽은 프레임은 프레임이 아닙니다.
- **검사 값이 맞지 않으면 아예 읽지 않습니다.** 어차피 맞겠지 하고 읽는 것이 손상된 노드 번호로 차량을
  보내는 길입니다.
- 응답 짝은 명령 id가 아니라 **시퀀스**로 맞춥니다(바이트 프레임에 id를 넣을 자리가 없으므로). 아웃박스의
  시도 번호를 시퀀스로 씁니다 — 이미 시도를 구분하는 값이기 때문입니다.
- `inbound`의 `ack`에 `sequence`를 적으면 그 프레임이 수신 확인입니다.

## PLC 레지스터 (`PLC_WORD_V1`)

```json
"area": { "driver": "modbus", "write": "40100", "read": "40200", "words": 16, "endian": "big", "poll-ms": 300 },
"handshake": {
  "write": ["command", "sequence", "nodes"],
  "then-set": "request",
  "accepted-bit": "accepted",
  "echo-slot": "seqEcho",
  "clear": "request",
  "timeout-ms": 2000
}
```

### 어느 선으로 말하는가 — `driver`

워드 블록을 읽고 쓴다는 **얼개는 같고** 다른 것은 선과 주소 표기뿐이라, 어댑터를 나누지 않고 `area`가
말합니다. `layout`·`handshake`·명령·수신 규칙은 어느 쪽이든 글자 그대로 같습니다.

| `driver` | 선 | 주소 표기 |
|---|---|---|
| `modbus` (적지 않으면 이것) | Modbus TCP, 기능 코드 3·16 | `40100` — 4x 관례의 100번째 보유 레지스터, 와이어에서는 99 |
| `mc` | MELSEC 통신 프로토콜, 3E 바이너리, 일괄 읽기 0401·일괄 쓰기 1401 | `D100` · `W1A0` · `R500` · `ZR1000` |

MELSEC 주소의 진법은 **제조사 관례 그대로**입니다 — 워드 레지스터(`D`·`R`·`ZR`)는 10진, 링크 레지스터
(`W`)는 16진입니다. 모든 MELSEC 매뉴얼과 그것을 읽는 모든 기술자가 그렇게 세므로, 문서에 `W1A0`이라
적으면 기술자가 뜻한 그 레지스터입니다.

**워드 디바이스만 주소가 됩니다.** 비트 디바이스를 워드 단위로 읽으면 응답이 오기는 하지만 서로 무관한
비트 열여섯 개가 올 뿐이고, 그중 어느 것을 뜻했는지 매핑 문서가 말할 방법이 없습니다. 비트가 필요하면
블록 안 워드의 비트로 적습니다(`{ "word": 5, "bit": 0 }`).

**순서가 안전의 전부입니다.**

1. 값을 먼저 씁니다 (`request` 비트는 내려둔 채로)
2. 그다음 `request` 비트를 세웁니다
3. 컨트롤러가 `accepted` 비트를 세우고 **`seqEcho`로 어느 명령을 받았는지 되돌려주면** 전달 성공
4. `request` 비트를 내립니다

2번이 1번 뒤인 이유: 중간에 컨트롤러가 읽어도 **아직 요청하지 않은 명령**을 볼 뿐입니다. 반대 순서였다면
절반만 쓰인 명령을 집어갈 수 있습니다.

3번의 에코가 없으면 **지금 수락인지 직전 명령의 수락인지 구분할 수 없습니다.** 그래서 필수입니다.

**수락이 없으면 `IN_DOUBT`입니다.** 값은 이미 레지스터에 남아 있고 컨트롤러가 나중에 집어갈 수도
있습니다. 이 시스템이 스스로 다시 쓰지 않으며, 재시작 대조에서 사람이 닫습니다.

**읽기는 폴링입니다.** 아무것도 오지 않고 블록을 계속 읽는 것이 전부이므로, **`poll-ms`가 위치 보고
지연 기준보다 충분히 작아야** 합니다. 그렇지 않으면 매 주기마다 모든 차량이 잠시 "보고 지연"이 됩니다.

## 설비 레지스터 (`EQUIPMENT_WORD_V1`)

차량이 아니라 **설비**가 뒤에 있는 같은 얼개입니다. `area`·`layout`·`driver`는 글자 그대로 같고, 다른
것은 **무엇을 말할 수 있는가**입니다.

```json
"adapter": "EQUIPMENT_WORD_V1",
"area": { "driver": "mc", "read": "D1000", "words": 8, "endian": "big", "poll-ms": 500 },
"identity": { "fromSlot": "unit" },
"inbound": [
  { "when": { "always": true },
    "report": {
      "status":      { "from": "slot.statusNow",
                       "values": { "0": "IDLE", "1": "READY", "2": "BUSY", "9": "ERROR" } },
      "available":   { "from": "slot.ableBit" },
      "callEnabled": { "from": "slot.callBit" }
    }}
]
```

| 보고 항목 | 뜻 |
|---|---|
| `status` | `IDLE` · `READY` · `BUSY` · `ERROR` · `OFFLINE` · `LOADING` · `UNLOADING` |
| `available` | 이 설비를 쓸 수 있는가 |
| `callEnabled` | 지금 호출을 받는가 |
| `online` | 관제가 판단합니다. 문서가 적지 않습니다 |

차량 어휘(`nodeId`·`batteryPct` 등)는 **쓸 수 없습니다.** 스토커에는 노드도 배터리도 없고, 적을 수 있게
두면 누군가는 적습니다.

- **읽기만 합니다.** 이 관제는 설비를 몰고 가지 않고 참작합니다. `outbound`를 적으면 그때는 핸드셰이크가
  필수가 되지만(레지스터는 값이 남으므로), 지금 이 시스템에는 설비에 **무엇을 쓸지 정하는 판단이 없습니다.**
- **침묵은 "그대로"가 아닙니다.** 멈춘 컨트롤러는 마지막 값을 계속 들고 있으므로, 가만히 두면 영원히
  `READY`로 읽힙니다. 몇 주기 동안 못 읽으면 그 설비는 **offline**이 되고, 이후 판단은 그것을 봅니다.
  마지막으로 말한 내용은 **지우지 않습니다** — 조용해질 때 무엇을 하고 있었는지가 알고 싶은 전부입니다.
- 설비 상태는 `equipment.device_state`에 들어갑니다. 시뮬레이션 상태(`equipment.simulation_state`)와
  **다른 테이블**입니다. 하나는 기계가 말한 것이고 하나는 누군가 시험 삼아 둔 것이라, 구분하지 못하면
  진짜 고장과 연습을 구분하지 못합니다.
- 반송 판단(`requireAvailable`)이 보는 것은 운전 모드가 정합니다. SIMULATION이면 시뮬레이션 상태, OPERATION이면
  기계가 말한 것입니다. **배선해 놓고 한 번도 못 들은 설비는 거절합니다**(`EQUIPMENT_OFFLINE`) — 소식 없는
  스토커가 바로 반송을 보내면 안 되는 그 설비입니다. 아무도 배선하지 않은 설비는 거절하지 않습니다.

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

`inbound`의 `report`가 만들 수 있는 이름은 **이 관제가 실제로 행동하는 항목뿐**입니다. 아래는 차량 어휘이고,
설비(`EQUIPMENT_WORD_V1`)는 [자기 어휘](#설비-레지스터-equipment_word_v1)를 씁니다.

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

- [sample-mappings/vda5050-v3.json](sample-mappings/vda5050-v3.json) — VDA5050 3.0 최소 반송 계약(order·instantActions 발행, state·connection 수신)
- [sample-mappings/pc-frame-example.json](sample-mappings/pc-frame-example.json) — 32바이트 고정 프레임, STX/ETX, XOR 체크섬
- [sample-mappings/plc-word-example.json](sample-mappings/plc-word-example.json) — Modbus TCP 보유 레지스터 16워드, 요청/수락 핸드셰이크
- [sample-mappings/equipment-mc-example.json](sample-mappings/equipment-mc-example.json) — 설비(스토커), MELSEC D1000부터 8워드 읽기 전용

넷 다 **가상의 값**입니다. 현장 값은 복사해서 고친 뒤 설정 화면에서 올립니다.

## 문서를 어디에 두는가

문서가 놓이는 자리는 **두 층**이고, 둘 사이의 순서가 핵심입니다.

| 층 | 어디 | 누가 바꾸는가 |
|---|---|---|
| 서버 기본 | `amhs.device.mappings` 디렉터리(기본 `config/mappings`)의 `*.json` | 배포에 실려 옵니다 |
| 사이트 설치 | `integration.message_mapping` 테이블 (설정 화면 "매핑 문서") | 관리자가 올립니다 |

**같은 이름이면 사이트가 올린 쪽이 이깁니다.** 직접 쓴 사이트에는 그럴 이유가 있기 때문입니다. 올린
문서를 내리면 서버 기본 문서가 다시 쓰입니다.

어느 층에 있든 문서의 `mapping` 이름이 곧 **프로토콜 프로파일의 adapterKey**입니다 — 차량 통신 설정이
그 이름으로 문서를 가리킵니다.

```
config/mappings/vda5050-v3.json   →  "mapping": "VDA5050-V3"
                                      프로토콜 프로파일 adapterKey = VDA5050-V3
```

읽히지 않는 **서버 기본 문서**는 다른 문서를 막지 않고 서버도 막지 않습니다. 잘못된 곳을 전부 로그에
남기고, 그 문서를 가리키던 차량은 "설정되지 않음"으로 남습니다. 방언을 읽을 수 없는 차량에게는 그것이
정직한 상태입니다.

### 올리고 내리기

| 요청 | 하는 일 |
|---|---|
| `GET  /sites/{site}/configuration/mappings` | 이 사이트가 올린 문서 목록(문서 본문까지) |
| `GET  …/mappings/available` | 프로파일이 가리킬 수 있는 모든 이름 — 올린 것과 서버 기본 |
| `GET  …/mappings/{code}` | 한 문서 |
| `POST …/mappings` | 문서 본문 그대로가 요청 본문. 이름은 문서 안의 `mapping` 값 |
| `DELETE …/mappings/{code}` | 내리기 |

- 설정 경로이므로 **Lv4 관리자**이고, 바꾸는 쪽은 **유지보수 상태**에서만 됩니다. 방언을 바꾸는 것은
  그 차량들에게 가는 모든 명령이 쓰이는 방식을 바꾸는 일입니다.
- 저장하기 **전에** 읽고 판정합니다. 받아들여진 문서는 동작하는 문서입니다 — 아니라면 차량이 움직이지
  않을 때 알게 됩니다. 거부는 `MAPPING_REJECTED`에 잘못된 곳을 전부 붙여서 돌려줍니다.
- 같은 이름을 다시 올리면 **그 문서를 고치는 것**이고 rev가 올라갑니다. 파일 안에서 이름을 바꿔 올리면
  다른 문서를 설치하는 것입니다 — 그렇게 한 사람이 뜻한 바가 그것입니다.
- 그 이름을 adapterKey로 쓰는 프로파일을 **차량 통신 설정이 가리키고 있으면 내리기를 거부합니다**(`MAPPING_IN_USE`).
  읽을 수 없는 방언을 가리키는 차량은 설정된 것처럼 보이면서 설정되지 않은 차량이고, 먼저 고쳐야 할
  것은 그렇게 가리키는 행입니다.
- 문서는 **올린 그대로** 보관하고 그대로 돌려줍니다. 받아서 다시 쓰면 사람이 쓴 파일과 더 이상 같지
  않은 파일을 돌려주게 됩니다.

## MQTT로 쓸 때

연결 프로파일을 `MQTT` · `BROKER` · 사용으로 두고, 프로토콜 프로파일의 adapterKey를 문서 이름으로,
차량 통신에 제조사·시리얼·인터페이스 이름을 적습니다. 토픽의 `{...}`는 여기서 채워집니다.

| 토픽 조각 | 어디서 |
|---|---|
| `{manufacturer}` | 차량 통신의 제조사 |
| `{serial}` | 시리얼 번호(없으면 외부 차량 ID) |
| `{prefix}` | 인터페이스 이름(없으면 `uagv`) |
| `{version}` | 프로토콜 버전의 major에 `v`를 붙인 값 (`3.0.0` → `v3`) |

발행은 QoS 1입니다. **브로커가 받았다는 확인까지가 `DELIVERED`**이고, 그것은 차량이 수행했다는 뜻이
아닙니다. 브로커에 연결되지 않으면 아무것도 쓰지 않았으므로 재전송 가능한 미전송이고, 발행은 했는데
확인이 없으면 결과 불명으로 남깁니다.

## 아직 아닌 것

- 화면에서 문서를 **고치는** 편집기. 지금은 내려받아 고친 뒤 다시 올립니다. 판정이 서버에 있으므로
  편집기를 둬도 거부 이유는 같지만, 고치는 자리가 파일이라는 점은 그대로입니다.
- 이적재 완료 같은 **작업 증거**의 매핑. 작업 상태를 바꾸는 판단은 링크가 아니라 작업 원장의 몫이므로
  별도 계약(`POST /transport-orders/{id}/transitions`)으로 둡니다.
- 코일·입력 레지스터(Modbus는 지금 보유 레지스터만), MELSEC의 비트 디바이스.
- 설비에 **쓰는** 것. 지금은 읽기만 합니다 — 이 시스템에 설비로 보낼 판단 자체가 아직 없습니다.
- 실장비·실제 컨트롤러 검증. 어댑터 넷 모두 시험용 서버를 상대로만 확인했습니다.
