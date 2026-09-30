# 경로 조회 계약

`GET /api/v1/sites/{siteId}/maps/{revisionId}/route?startNodeId={id}&goalNodeId={id}`

지도와 같은 site/revision을 사용하며 정적 주행 시간으로 경로를 제안합니다. 존재하지 않는 site/revision은 HTTP 404입니다. 경로 없음은 정상 계산 결과로 HTTP 200과 사유를 반환합니다.

| 결과 | 의미 |
| --- | --- |
| FOUND | 순서 있는 nodeIds·edgeIds와 costSeconds 반환 |
| SAME_NODE | 사용 가능한 동일 시작/도착, 노드 1개·구간 없음·비용 0 |
| UNREACHABLE | 경로 없음, 빈 목록·비용 null과 reason 반환 |

사유는 START_UNKNOWN, GOAL_UNKNOWN, START_BLOCKED, GOAL_BLOCKED, NO_PATH입니다. 알려지지 않은 위치를 초기 설정 노드로 대체하지 않습니다. 노드 사용 가능 여부와 구간 enabled를 모두 검사합니다. 비용은 유한한 비음수 초 단위이며, 평행 구간과 동률 입력은 결정적으로 처리합니다.

내부 비용 정책은 정적 비용 또는 구간 배율·진입 노드 벌점을 받습니다. 배율은 1 이상, 벌점은 0 이상이어야 하며 호출 시 복사한 값을 사용합니다. 벌점은 진입 차단이 아니므로 다른 경로가 없으면 해당 경로가 선택될 수 있습니다. 실제 구간 진입은 별도의 예약·점유 검사를 통과해야 합니다.

현재 HTTP API에는 실시간 혼잡 데이터와 파라미터 저장 기능이 연결되지 않았습니다. 경로 조회는 설정 변경·예약·명령 송신을 수행하지 않습니다.
