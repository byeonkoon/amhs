# 차량 TCP 연결 계약 (JSON_LINE_V1)

실제 차량과 주고받는 첫 번째 어댑터의 계약입니다. **특정 차량 제조사의 프로토콜이 아니라 이 시스템이 정한
형식**이며, 라인 앞단의 제어기나 게이트웨이가 차량의 고유 프로토콜로 번역하는 것을 전제로 합니다. 그래야
차량이 바뀌어도 관제의 판단 로직은 그대로 둘 수 있습니다.

- 어댑터 키: `JSON_LINE_V1` (프로토콜 프로파일의 `adapterKey`에 적습니다)
- 역할: **AMHS가 TCP 클라이언트**, 제어기/게이트웨이가 서버
- 연결 단위: 연결 프로파일 하나당 TCP 연결 하나. 한 연결에 여러 대의 차량이 붙습니다
- 인코딩: UTF-8, **한 줄에 JSON 객체 하나**, `\n`으로 끝냄. 한 프레임 최대 64 KiB

## 보내는 쪽 (AMHS → 제어기)

```json
{"type":"command","commandId":"order-42-assignment-7-MOVE","vehicle":"V-01",
 "command":"MOVE","sentAt":"2026-10-10T23:30:11.244+09:00","payload":{"authorizedNodeIds":[203,204,207]}}
```

- `vehicle` — 차량 통신 설정의 `외부 차량 ID`. 제어기가 아는 이름이며, 관제의 내부 차량 번호가 아닙니다.
- `commandId` — 이 명령의 고유 id. 응답은 이 값으로 짝을 맞춥니다.
- `payload` — 결정이 만든 그대로를 실어 보냅니다. 어댑터가 해석하지 않습니다.

## 받는 쪽 (제어기 → AMHS)

| type | 뜻 | 필수 필드 |
|---|---|---|
| `ack` | 그 명령을 **받았다**. 수행 완료가 아닙니다 | `commandId` |
| `report` | 차량의 현재 상태 | `vehicle` |
| `ping` | 살아 있는지 확인 | — |
| `pong` · `hello` | 무시합니다 | — |

```json
{"type":"ack","commandId":"order-42-assignment-7-MOVE"}
{"type":"report","vehicle":"V-01","nodeId":207,"mode":"REMOTE","status":"MOVING",
 "carrier":true,"batteryPct":61.5,"destinationChangeEnabled":true}
```

- `mode` — `MANUAL` · `SEMI_AUTO` · `REMOTE`. `REMOTE`만 관제가 명령할 수 있는 상태입니다.
- `status` — `IDLE` · `MOVING` · `ERROR` · `PAUSED` · `CHARGING` · `LOADING` · `UNLOADING` · `RESET`.
- 모르는 값이 오면 **그 보고를 버리고 로그에 남깁니다.** 아는 값으로 바꿔 적지 않습니다 — 차량이 이상한
  것을 보고하는 상태는 보여야 할 상태입니다.
- 보고는 `source=DEVICE`로 기록됩니다. 시뮬레이터가 쓴 것(`SIMULATED`)과 구분되며, 진단 화면의 차량 링크
  상태도 이 값으로 "한 번도 못 들음 / 오래됨 / 최근"을 가립니다.
- 이적재 완료 같은 **작업의 증거**는 이 링크가 아니라 `POST /transport-orders/{id}/transitions`로 보냅니다.
  작업 상태를 바꾸는 일은 링크가 아니라 작업 원장의 판단이기 때문입니다.

## 전송 결과 판정

이 세 가지가 `DeviceGateway`가 명령을 정산하는 기준 전부입니다.

| 상황 | 판정 | 다시 보내도 되는가 |
|---|---|---|
| 연결이 없거나 거절됨 (한 바이트도 쓰지 않음) | `NOT_DELIVERED` · `LINK_REFUSED` | 예 |
| 이 차량에 쓸 링크 설정이 없음 | `NOT_DELIVERED` · `ADAPTER_NOT_CONFIGURED` | 예 |
| 쓰고 나서 `ack`를 제한 시간 안에 받음 | `DELIVERED` | — (수신 확인일 뿐 수행 완료가 아님) |
| 썼는데 `ack`가 오지 않음 | `IN_DOUBT` · `ACK_TIMEOUT` | **아니오** |
| 쓰는 도중 끊김 | `IN_DOUBT` · `WRITE_FAILED` | **아니오** |

결과를 모르는 명령은 자동으로 정리하지 않습니다. 재시작 대조 화면에서 운전자가 차량이 정지한 것을 확인한
뒤 "받았을 수 있음"으로 닫습니다.

## 설정

1. **연결 프로파일** — `TCP` · `CLIENT` · 주소 · 포트 · 사용. TCP 클라이언트만 사용으로 둘 수 있습니다
   (`SERVER`로 받는 쪽, MQTT는 아직 어댑터가 없어 거절합니다).
2. **프로토콜 프로파일** — `adapterKey = JSON_LINE_V1`.
3. **차량 통신** — 차량 ↔ 두 프로파일 연결, 제어기가 아는 외부 차량 ID, 사용.
   셋이 모두 갖춰져야 "사용"으로 둘 수 있고, 그렇지 않으면 `ADAPTER_NOT_IMPLEMENTED` 또는
   `CONNECTION_NOT_IN_USE`로 거절합니다 — 연결되지 않은 차량이 연결된 것처럼 보이는 설정을 막습니다.

운전 모드가 `SIMULATION`인 사이트의 명령은 이 링크로 나가지 않고 내부 시뮬레이터로 갑니다. 링크는
`OPERATION`에서만 씁니다.

## 동작 방식

- 연결은 **보낼 것이 생겼을 때** 엽니다. 설정만 해 두고 배선 전인 현장이 로그를 채우지 않습니다.
- 연결이 실패하면 `retry-after-ms` 동안 다시 시도하지 않습니다. 죽은 라인에 명령이 몰려도 시도는 한 번입니다.
- 연결 프로파일의 주소가 바뀌면 열려 있던 연결을 닫고 새 주소로 엽니다.
- 읽기 스레드가 끊김을 감지하면 기다리던 명령을 모두 `IN_DOUBT`로 풀어 줍니다. 보낸 것을 못 보낸 것으로
  바꾸지 않습니다.

| 설정 | 기본값 |
|---|---|
| `amhs.device.tcp.connect-timeout-ms` | 2000 |
| `amhs.device.tcp.ack-timeout-ms` | 3000 |
| `amhs.device.tcp.retry-after-ms` | 1000 |

## 아직 아닌 것

- 받는 쪽(TCP SERVER)으로 차량이 접속해 오는 구성.
- MQTT 브로커와 VDA5050 매핑.
- PC·PLC 고유 프로토콜의 직접 매핑. 지금은 라인 앞단의 번역을 전제로 합니다.
- 실제 장비와의 연결 검증. 이 계약은 시험용 소켓을 상대로만 확인했습니다.
