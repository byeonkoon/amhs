# 화면 반영 지연 재현 절차

차량이 보고한 시각부터 그 내용이 **화면에 그려지기까지** 걸리는 시간을 재는 절차입니다. 브라우저가
있어야만 잴 수 있으므로 손으로 합니다.

**바닥과 처리량은 여기서 재지 않습니다.** 같은 노드에 두 차량·쥐지 않은 노드 점유·교착, 완료 수와
소요 시간, 제어 루프 주기는 [부하 하네스](../scripts/load-run.ps1)가 한 줄로 재고 뒤에 남은 것까지
묻습니다(`scripts/load-invariants.sql`). 같은 것을 두 절차가 서로 다르게 재면 둘 다 못 믿습니다.

```powershell
.\scripts\load-run.ps1 -Minutes 15 -PerMinute 240 -PerVehicle 2 -Map docs/sample-maps/large-site.json -User <Lv4 계정> -Password <비밀번호>
```

하네스는 끝에 **스스로 판정하고 종료 코드를 냅니다** — 0 통과, 1 바닥이 틀렸다, 2 판정 불가(사이트가
진행을 멈췄거나 PC가 자다 깼다). 판정 불가가 실패가 **아닌** 것이 중요합니다: 일어나지 않은 측정은
결함의 증거가 아닙니다. 지도를 바꾸지 않더라도 하네스 전용 DB(`load`)에서는 **빈 바닥에서 시작**합니다.

## 더 큰 바닥

지도 생성기가 배수를 받습니다. 라인이 길어지고 베이·포트·차량이 그만큼 늘어납니다(루프를 넓히거나
겹치지는 않습니다 — 노드 예약만으로 충분한 이유가 그 모양이기 때문입니다).

```powershell
node scripts/build-large-site.cjs .local/xl-site.json 3   # 787노드 · 192포트 · 차량 75대
node scripts/check-map-geometry.cjs .local/xl-site.json   # 여유 거리·연결·막다른 길
.\scripts\load-run.ps1 -Minutes 15 -Map .local/xl-site.json -User <Lv4 계정> -Password <비밀번호>
```

배수 1은 저장소의 `docs/sample-maps/large-site.json`과 바이트까지 같습니다. 검사 스퍼와 OHT 대기 스퍼는
바닥이 길어져도 하나이고, 충전·주차 베이는 차량 10대당 하나로 늘어납니다.

## 정기 실행

판정이 붙었으므로 사람이 볼 필요가 없습니다. Windows 작업 스케줄러에 등록합니다.

```powershell
.\scripts\load-regression-schedule.ps1                      # 매일 03:30, 12분
.\scripts\load-regression-schedule.ps1 -At 02:00 -Weekly Sunday
.\scripts\load-regression-schedule.ps1 -Show                # 다음 실행과 마지막 판정
.\scripts\load-regression-schedule.ps1 -Remove
```

작업이 실행하는 것은 [`scripts/load-regression-task.ps1`](../scripts/load-regression-task.ps1)이고,
그 **종료 코드가 작업의 Last Run Result**가 됩니다. 실행한 내용 전체는 `.local/load-runs`에
`scheduled-*.log`로, 보고서는 같은 자리에 `report-*.txt`로 남습니다(최근 14개만 보관).

정하고 가는 것 셋.

| | |
|---|---|
| **등록한 사람으로, 로그온 중에** 실행합니다. 비밀번호를 묻지도 저장하지도 않습니다 | 격리 클러스터 비밀번호는 그 계정의 키로 저장돼 있어 다른 계정은 읽지 못하고, 비밀번호를 요구하는 작업은 비밀번호가 한 군데 더 생기는 일입니다 |
| **PC를 깨우지 않습니다.** 놓친 실행은 다음 기회에 시작합니다 | 회귀는 PC의 아침값은 되지만 밤값은 아닙니다. 일정이 더 중요하면 `-WakeToRun`을 씁니다 |
| **배터리로는 시작하지 않고**, 시작한 뒤에 전원이 빠져도 죽이지 않습니다 | 12분 내내 바닥을 포화시키는 실행입니다. 노트북에서는 전원이 올 때까지 작업이 `Queued`로 기다립니다. 그래도 돌리려면 `-OnBatteryToo` |

놓친 실행은 다음 기회에 시작하므로, **한밤의 실행이 낮에 따라잡아 돌 수 있습니다.** 실제로 그랬고, 그때
손으로 시작한 실행과 겹쳤습니다. 하네스가 "다른 응용이 이 DB에 붙어 있다"로 한쪽을 거부해서 둘을 섞어
재지는 않았고, 거부된 실행은 실패가 아니라 **판정 불가(2)**입니다. 낮에 도는 것이 싫으면 트리거 시간을
PC가 깨어 있는 시간으로 잡는 편이 `-StartWhenAvailable`을 끄는 것보다 낫습니다 — 끄면 자는 밤마다
실행이 그냥 없어집니다.

실장비나 실제 통신 어댑터 시험은 어느 쪽도 아닙니다.

## 무엇을 시험하는가

| 구분 | 이 절차의 범위 |
| --- | --- |
| 현재 구현 | 배차·경로·노드 예약·연속 이동 허가·시뮬레이터 이적재·실시간 화면 피드 |
| 프로토콜 fixture | **포함하지 않음.** PC·PLC·MQTT/VDA5050 어댑터는 후속 범위다(A24~A29). |
| 실장비 | **검증하지 않음.** 차량 외형·제동·통신 지연·장비 응답은 시뮬레이터 모델이다. |

## 환경

- 지도: 대규모 예제(revision 3). 노드 144개, 구간 164개, 포트 16개.
- 차량: 설정된 OHT 15대·AGV 10대 중 각 5대만 사용하고 나머지는 사용 안 함.
- 운전: SIMULATION, 자동 배차 켬.
- 작업 발생기: `amhs.simulation.load.per-minute`(분당 작업 수)와 `amhs.simulation.load.seed`(포트 선택 난수 시드).
  - 같은 차종이 닿는 두 포트 사이로만 만든다.
  - 차종별로 열린 작업 수가 그 차종의 사용 차량 수를 넘지 않는다.
- DB: 프로젝트 격리 클러스터(127.0.0.1:55432)의 `amhs_soak`. 실행할 때마다 새로 만든다. 개발 DB와 운영 DB는 건드리지 않는다.

## 절차

1. 도구와 격리 클러스터를 준비한다(한 번만): `scripts/setup-tools.ps1`, `scripts/test-postgres.ps1`. 시험이 서버 jar도 만든다.
2. 다른 서버가 8080을 쓰고 있으면 멈춘다.
3. `powershell -ExecutionPolicy Bypass -File scripts/soak.ps1 -PerMinute 20 -Seed 42`를 실행한다.
   - `amhs_soak`을 새로 만들고, 한 번 기동해 migration한 뒤 `scripts/soak-fixture.sql`을 적용한다.
   - 이어서 작업 발생기를 켠 채 서버를 띄운다. 서버는 계속 실행된다.
4. 웹 개발 서버(`web`에서 `npm run dev`)를 띄우고 브라우저 두 개(또는 탭 두 개)로 운전 현황을 연다.
5. 화면 반영 지연을 잴 브라우저에서 개발자 콘솔에 `localStorage.setItem('amhs.latencyProbe','1')`를 입력하고 새로고침한다.
   - 실시간 피드가 새 차량 보고를 받을 때마다, 보고 시각부터 그 내용이 반영된 다음 화면 프레임까지의 시간을 `window.__amhsLatency`에 쌓는다.
   - 서버와 브라우저가 같은 PC 시계를 쓸 때만 의미가 있다.
6. 정해 둔 시간 동안 둔 뒤 결과를 읽는다.
   - 서버: `GET /api/v1/sites/1/simulation/load`
     - 작성·거부 작업 수, 관찰 횟수
     - 같은 노드 중복·쥐지 않은 노드 점유·교착이 보인 관찰 수
     - 최대 열린 작업 수, 완료·취소·실패·복구 대기 수
     - 완료 작업의 접수→완료 시간 중앙값·p95
   - 화면: 콘솔에서 `window.__amhsLatency`를 정렬해 p95를 구한다. 산식은 가까운 순위다. 표본 n개를 오름차순으로 놓고 ⌈0.95n⌉번째 값을 쓴다.
7. 끝나면 soak 서버 프로세스를 멈춘다.

## 판정 기준

- 같은 노드 중복, 쥐지 않은 노드 점유: 0이어야 한다. 하나라도 있으면 실패다.
- 교착 관찰: 0이 목표다. 생기면 원인을 적는다.
- 상태 보고→화면 반영 p95: 1초 이내가 목표다. 미달하면 원인을 남긴다. 측정하기 전에는 성능 실적으로 쓰지 않는다.

## 기록

실행 결과는 [검증 기록](verification.md)에 남긴다. 기록할 항목은 다음과 같다.

- 실행 조건: PC·커밋·분당 작업 수·시드·실행 시간·브라우저 수
- 서버 보고 값
- 화면 지연 표본 수와 p95
