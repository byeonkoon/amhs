# 지도 파일 형식 (JSON)

사이트의 지도 한 벌을 파일 하나로 주고받습니다. 올리면 **새 지도 버전**이 되고, 그 버전은 준비(DRAFT) 상태로 들어옵니다 — 올리는 것만으로는 운전 중인 지도가 바뀌지 않습니다. 사용할 버전을 고르는 것은 `Resources → 지도 버전`의 별도 결정이며, 진행 중인 작업이 없을 때만 됩니다.

내보내기는 같은 형식으로 나옵니다. 받아서 고쳐 다시 올리면 또 하나의 버전이 됩니다.

## 기준

- **서로를 코드로 가리킵니다. id는 쓰지 않습니다.** id는 그 파일을 담고 있던 데이터베이스의 것입니다. 다른 DB에서는 같은 숫자가 다른 것을 뜻하므로, id가 든 파일은 조용히 다른 지도가 됩니다.
- **빠진 항목은 평범한 값으로 채웁니다.** `enabled`를 적지 않으면 사용, 플래그를 적지 않으면 꺼짐, 구간 비용을 적지 않으면 두 노드 사이의 실제 거리입니다. 적지 않은 것과 0을 구분합니다.
- **전부 검사한 뒤에 씁니다.** 절반만 들어간 지도는 거절된 지도보다 나쁩니다 — 지도처럼 보이기 때문입니다.
- 비어 있는 목록은 오류가 아닙니다. 차량을 정하기 전에 선로만 그린 상태는 준비 중인 지도의 정상적인 모습입니다.

## 바로 쓸 수 있는 파일

[sample-maps/large-site.json](sample-maps/large-site.json) — 대형 레이아웃 한 벌입니다. 노드 249개(**OHT 132 · AGV 117**), 구간 279개(그중 67개가 커브), 포트 10개, 차량 25대(OHT 15 · AGV 10), 충전 4자리, 대기 OHT 2 · AGV 8자리, 점검 Zone 1개.

두 네트워크의 모양이 다릅니다. OHT 본선은 구조물을 따라가는 **모서리를 둥글린 사각 루프**, AGV 본선은 바닥 통로를 돌리는 **양 끝이 반원인 루프**입니다. Bay는 직선 구간에만 답니다 — 곡선에서 갈라지면 본선을 비스듬히 침범합니다. Bay 안의 커브는 본선과 **반대 방향으로** 휩니다(분기가 본선에서 멀어지는 쪽으로 읽히도록).

충전 자리와 대기 자리는 한 Bay 안에 모아 두고, 각 자리를 lane에서 **따로 갈라진 가지**에 답니다. 한 줄로 늘어놓으면 앞차가 뒤차의 출발 시점을 쥐게 되고, 주차된 차는 비켜 줄 이유가 없어서 뒤쪽 충전기는 아무도 쓸 수 없게 됩니다. 자리 수는 차량 대수보다 하나 이상 많게 둡니다 — 충전을 마친 차가 비켜 설 곳이 있어야 그 충전기가 다음 차에게 넘어갑니다.

`Resources → 지도 버전 → JSON 지도 파일 올리기`로 그대로 올리면 새 버전이 됩니다. 규모를 바꾸려면 이 파일을 고쳐서 올리는 것이 가장 빠릅니다 — 한 줄이 한 노드이므로 복사해 좌표만 바꾸면 됩니다.

## 예시

```json
{
  "nodes": [
    { "code": "OHT-M001", "xMm": 20000, "yMm": 20000 },
    { "code": "OHT-M002", "xMm": 27000, "yMm": 20000 },
    { "code": "OHT-B01", "xMm": 23500, "yMm": 29000 },
    { "code": "OHT-WAIT1", "xMm": 13000, "yMm": 24000, "waitFor": "OHT" },
    { "code": "AGV-CHG1", "xMm": 8000, "yMm": 112000, "chargePoint": true }
  ],
  "segments": [
    { "from": "OHT-M001", "to": "OHT-M002" },
    { "from": "OHT-M001", "to": "OHT-B01", "curveMm": 2200 },
    { "from": "OHT-B01", "to": "OHT-M002", "curveMm": 2200, "costMm": 5000 }
  ],
  "ports": [
    { "code": "BAY-O01", "node": "OHT-B01", "portType": "LOAD", "accessVehicleType": "OHT",
      "equipment": "STK-01" }
  ],
  "vehicles": [
    { "code": "OHT-11", "name": "OHT 11", "vType": "OHT", "initialNode": "OHT-M001", "speedMmS": 2000 },
    { "code": "AGV-11", "vType": "AGV", "initialNode": "AGV-CHG1", "charge": "AGV-CHG1",
      "chargingPolicy": "NEAREST", "speedMmS": 1000 }
  ],
  "zones": [
    { "code": "INSPECTION", "capacity": 4, "kind": "MAINTENANCE", "nodes": ["OHT-WAIT1"] }
  ]
}
```

## 항목

### nodes

| 칸 | 뜻 |
|---|---|
| `code` | 사람이 부르는 이름. 한 지도 안에서 하나씩. |
| `xMm`, `yMm` | 평면도 좌표(mm). |
| `enabled` | 사용 여부. 기본 사용. |
| `stopNode` | 여기서 이동 허가가 끝나고 차량이 섭니다. |
| `mergePoint` | 합류 지점. 먼저 요청한 차량이 먼저 지나갑니다. |
| `chargePoint` | AGV가 충전하는 자리. |
| `waitFor` | `"OHT"` 또는 `"AGV"`. 그 차종이 할 일 없이 서 있을 자리입니다. |

`chargePoint`와 `waitFor`를 함께 둘 수 없습니다. 대기하려고 충전기에 선 차량은 아무도 쓸 수 없는 충전기입니다.

### segments

한 방향입니다. 양방향으로 다니게 하려면 반대 방향도 따로 적습니다.

| 칸 | 뜻 |
|---|---|
| `from`, `to` | 노드 코드. 같을 수 없고, 같은 (from, to) 쌍은 하나뿐입니다. |
| `costMm` | 주행 비용. 생략하면 두 노드 사이의 직선 거리. |
| `enabled` | 통행 가능 여부. 기본 가능. |
| `curveMm` | 구간 가운데가 직선에서 벗어나는 거리. Bay 진입처럼 직각으로 돌 수 없는 자리를 표현합니다. 0은 직선. |

### ports

| 칸 | 뜻 |
|---|---|
| `code` | 포트 이름. 한 지도 안에서 하나씩. |
| `node` | 어느 노드에서 인수도하는지. |
| `portType` | `LOAD`/`UNLOAD` 등. 생략하면 `LOAD`. |
| `accessVehicleType` | `OHT` 또는 `AGV`. 그 포트에 닿을 수 있는 차종입니다. |
| `equipment` | 이 포트에서 인수도하는 **설비 코드**. 생략하면 연결하지 않습니다. |

**설비는 지도의 것이 아닙니다.** 사이트의 것이고 지도 버전과 무관하게 살아 있으므로, `equipment`는 문서
바깥을 가리키는 유일한 참조입니다. 그래서 **사이트에 없는 이름을 적으면 거절합니다**(`PORT_EQUIPMENT_UNKNOWN`)
— 조용히 연결을 비우면 관제가 영영 묻지 않을 인수도 자리가 됩니다. 설비를 먼저 등록한 뒤 지도를 올립니다.

### vehicles

| 칸 | 뜻 |
|---|---|
| `code` | 차량 이름. 한 지도 안에서 하나씩(사이트 전체가 아니라 그 지도 안입니다). |
| `vType` | `OHT` 또는 `AGV`. |
| `initialNode` | 처음 서 있는 자리. |
| `home` | 자기 대기 노드. 없으면 같은 차종의 `waitFor` 노드 중 가까운 빈 자리를 씁니다. |
| `charge` | 자기 충전 자리. `chargingPolicy`가 `DISABLED`가 아니면 반드시 필요합니다. |
| `chargingPolicy` | `DISABLED`(레일 급전) / `HOME`(자기 자리) / `NEAREST`(가까운 빈 충전기). 생략하면 `DISABLED`. |
| `speedMmS` | 주행 속도. 생략하면 1000. |
| `enabled`, `assignmentEnabled` | 사용 여부와 배차 허용. 기본 둘 다 참. |

`enabled`(사용)와 `assignmentEnabled`(배차)는 다릅니다. 사용 금지는 그 차량을 모든 것에서 제외하고, 배차 미허용은 반송 작업 할당만 제외합니다 — 충전·양보·대기 복귀는 그대로 합니다.

### zones

| 칸 | 뜻 |
|---|---|
| `code` | Zone 이름. |
| `capacity` | 동시에 들어갈 수 있는 차량 수(1~100). |
| `kind` | `TRAFFIC`(지나가는 곳, 일이 끝나면 내보냅니다) 또는 `MAINTENANCE`(머무는 곳). 생략하면 `TRAFFIC`. |
| `nodes` | 이 Zone에 속한 노드 코드들. 한 노드는 한 Zone에만 속합니다. |

## 거절 사유

파일이 스스로 어긋나면 아무것도 쓰지 않고 거절하며, 무엇이 문제인지 코드로 알려 줍니다.

`MAP_HAS_NO_TRACK`(노드 없음) · `NODE_CODE_REPEATED` · `NODE_POSITION_REQUIRED` · `WAIT_FOR_INVALID` · `CHARGE_POINT_NOT_A_WAIT_PLACE` · `SEGMENT_NODE_UNKNOWN` · `SEGMENT_ENDS_AT_ITS_START` · `SEGMENT_REPEATED` · `PORT_CODE_REPEATED` · `PORT_NODE_UNKNOWN` · `PORT_VEHICLE_TYPE_INVALID` · `VEHICLE_CODE_REPEATED` · `VEHICLE_TYPE_INVALID` · `VEHICLE_NODE_UNKNOWN` · `CHARGING_NEEDS_A_CHARGE_NODE` · `ZONE_CODE_REPEATED` · `ZONE_CAPACITY_INVALID` · `ZONE_KIND_INVALID` · `ZONE_NODE_UNKNOWN` · `ZONE_NODE_IN_TWO_ZONES`

## 권한

올리기·내보내기·버전 적용은 Lv4 관리자입니다. 올리기는 운전 중인 지도를 건드리지 않으므로 유지보수 상태를 요구하지 않습니다. **버전 적용은 요구합니다** — 그리고 진행 중인 작업, 끝나지 않은 이동, 반납되지 않은 구간 점유가 하나라도 있으면 거절합니다.
