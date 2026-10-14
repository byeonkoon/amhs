import type {
  ConnectionSettings, EquipmentCommunicationSettings, OperationOptions, ProtocolSettings,
  VdaProfileSettings, VehicleSettings,
} from '../../api';

/**
 * Field dependencies, mirrored from the server contract so the form cannot offer a
 * combination the server will reject. The server still validates; this only stops the
 * user from building an invalid request in the first place.
 */

export const CONTROL_AUTHORITY = ['CONTROLLER_MANAGED', 'VENDOR_MANAGED'] as const;
export const BCR_POLICY = ['DISABLED', 'REQUIRED'] as const;
export const CHARGING_POLICY = ['DISABLED', 'HOME', 'NEAREST'] as const;

/** OHT does not charge in this system, so the policy is fixed and the node is not used. */
export function chargingPolicyOptions(vType: VehicleSettings['vType']): readonly string[] {
  return vType === 'OHT' ? ['DISABLED'] : CHARGING_POLICY;
}
export function chargeNodeRequired(v: Pick<VehicleSettings, 'vType' | 'chargingPolicy'>): boolean {
  return v.vType !== 'OHT' && v.chargingPolicy !== 'DISABLED';
}
export function vehicleIssues(v: VehicleSettings): string[] {
  const out: string[] = [];
  if (!v.code.trim()) out.push('차량 코드를 입력해 주세요.');
  if (!v.name.trim()) out.push('차량 이름을 입력해 주세요.');
  if (!(v.widthMm > 0 && v.lengthMm > 0 && v.heightMm > 0)) out.push('가로·세로·높이는 모두 1 이상이어야 합니다.');
  if (!(v.initialNodeId > 0)) out.push('초기 노드를 선택해 주세요.');
  if (v.vType === 'OHT' && (v.chargingPolicy !== 'DISABLED' || v.chargeNodeId))
    out.push('OHT는 충전을 사용하지 않습니다. 충전 정책은 DISABLED여야 합니다.');
  if (chargeNodeRequired(v) && !v.chargeNodeId) out.push('충전 정책을 사용하면 충전 노드가 필요합니다.');
  return out;
}

/**
 * The site communication type decides the VDA profile field: PC/PLC keep it empty,
 * MQTT needs one for every vehicle in use.
 */
export function vehicleVdaIssues(v: VehicleSettings, commType: OperationOptions['commType'] | null): string[] {
  if (commType === null) return [];
  if (commType !== 'MQTT') return v.vdaProfileId ? ['통신 방식이 MQTT가 아니면 VDA 프로파일은 공란이어야 합니다.'] : [];
  return v.enabled && !v.vdaProfileId ? ['MQTT 사이트에서 사용하는 차량은 VDA 프로파일이 필요합니다.'] : [];
}

export const COMM_TYPES = ['PC', 'PLC', 'MQTT'] as const;
export const MORE_TYPES = ['NODE', 'DISTANCE'] as const;
export function operationIssues(o: OperationOptions): string[] {
  const out: string[] = [];
  const int = (v: number, lo: number, hi: number) => Number.isInteger(v) && v >= lo && v <= hi;
  if (!int(o.nodeCount, 1, 100)) out.push('허가 노드 수는 1~100 사이 정수여야 합니다.');
  if (!int(o.moreRemainingNodes, 1, 99)) out.push('MORE 남은 노드 수는 1~99 사이 정수여야 합니다.');
  else if (o.moreRemainingNodes >= o.nodeCount) out.push('MORE 남은 노드 수는 허가 노드 수보다 작아야 합니다. 같거나 크면 허가 직후 바로 MORE가 나갑니다.');
  if (!int(o.moreRemainingPercent, 1, 99)) out.push('MORE 남은 거리 비율은 1~99% 사이 정수여야 합니다.');
  return out;
}

const TOPIC_SAFE = /^[^\s/+#]+$/;
const ACTION = /^[A-Za-z0-9_.-]{1,64}$/;
export function vdaProfileIssues(p: VdaProfileSettings): string[] {
  const out: string[] = [];
  if (!/^[A-Z0-9_-]{1,32}$/.test(p.code)) out.push('코드는 대문자·숫자·_·- 32자 이내입니다.');
  if (!TOPIC_SAFE.test(p.interfaceName)) out.push('인터페이스에 공백이나 / + # 를 쓸 수 없습니다.');
  if (!TOPIC_SAFE.test(p.manufacturer)) out.push('제조사에 공백이나 / + # 를 쓸 수 없습니다.');
  if (!/^v\d{1,3}$/.test(p.topicVersion)) out.push('토픽 버전은 v2, v3 형식입니다.');
  if (!/^\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(p.jsonVersion)) out.push('JSON 버전은 1.0.0, 3.0.0 형식입니다.');
  if (!ACTION.test(p.sourceAction)) out.push('출발지 동작은 영문·숫자·_·.·- 64자 이내입니다(쉼표 불가).');
  if (!ACTION.test(p.destAction)) out.push('목적지 동작은 영문·숫자·_·.·- 64자 이내입니다(쉼표 불가).');
  if (p.sourceAction === p.destAction) out.push('출발지 동작과 목적지 동작은 달라야 합니다.');
  return out;
}

/** TCP connects as client or server; MQTT only ever points at a broker. */
export function roleOptions(commType: ConnectionSettings['commType']): readonly string[] {
  return commType === 'TCP' ? ['CLIENT', 'SERVER'] : ['BROKER'];
}
export function equipmentLinkIssues(c: EquipmentCommunicationSettings): string[] {
  const out: string[] = [];
  if (!c.externalUnitId.trim()) out.push('외부 설비 ID를 입력해 주세요.');
  if (!(c.connectionProfileId > 0)) out.push('연결 프로파일을 지정해 주세요.');
  if (!(c.protocolProfileId > 0)) out.push('프로토콜 프로파일을 지정해 주세요.');
  return out;
}
export function connectionIssues(c: ConnectionSettings): string[] {
  const out: string[] = [];
  if (!c.host.trim()) out.push('호스트를 입력해 주세요.');
  else if (!/^[a-zA-Z0-9.:%_-]+$/.test(c.host)) out.push('호스트에 사용할 수 없는 문자가 있습니다.');
  if (!(c.port >= 1 && c.port <= 65535)) out.push('포트는 1~65535 범위여야 합니다.');
  if (!roleOptions(c.commType).includes(c.role)) out.push(`${c.commType} 연결의 역할은 ${roleOptions(c.commType).join(' 또는 ')}입니다.`);
  return out;
}

export const PROTO_TYPES = ['PC', 'PLC', 'VDA5050'] as const;
const VDA_VERSIONS = ['2.0.0', '2.1.0', '3.0.0'] as const;
/** Only VDA5050 has a fixed version list; the others carry a free-form version string. */
export function versionOptions(protoType: ProtocolSettings['protoType']): readonly string[] | null {
  return protoType === 'VDA5050' ? VDA_VERSIONS : null;
}
export function protocolIssues(p: ProtocolSettings): string[] {
  const out: string[] = [];
  if (!p.protoVersion.trim()) out.push('프로토콜 버전을 입력해 주세요.');
  if (!p.adapterKey.trim()) out.push('어댑터 키를 입력해 주세요.');
  const allowed = versionOptions(p.protoType);
  if (allowed && !allowed.includes(p.protoVersion)) out.push(`VDA5050은 ${allowed.join(', ')}만 지원합니다.`);
  return out;
}

/**
 * Enabling a profile would start a device adapter, and none are implemented yet.
 * The server rejects it, so the toggle stays off with the reason shown.
 */
/** Said when a connection is switched on for a kind of link this build cannot open. */
export const ADAPTER_DISABLED_REASON = '이 조합으로는 열 수 있는 장비 어댑터가 없습니다. TCP(CLIENT·SERVER)와 MQTT(BROKER)만 사용으로 둘 수 있습니다.';

/** Server rejection codes rendered as sentences the operator can act on. */
export function explainCode(code: string): string {
  const map: Record<string, string> = {
    OHT_CHARGING_NOT_SUPPORTED: 'OHT는 충전을 사용할 수 없습니다. 충전 정책을 DISABLED로 두세요.',
    CHARGE_NODE_REQUIRED: '충전 정책을 사용하면 충전 노드를 지정해야 합니다.',
    INVALID_CONNECTION_ROLE: '선택한 통신 방식에 맞지 않는 역할입니다.',
    UNSUPPORTED_VDA_VERSION: '지원하지 않는 VDA5050 버전입니다.',
    ADAPTER_NOT_IMPLEMENTED: ADAPTER_DISABLED_REASON,
    CONNECTION_NOT_IN_USE: '연결 프로파일이 사용으로 되어 있지 않거나, 열 수 있는 종류가 아닙니다. 연결 탭에서 먼저 사용으로 두세요.',
    CONFIGURATION_WRITES_DISABLED: '서버에서 설정 변경이 비활성화되어 있습니다.',
    PRINCIPAL_REQUIRED: '설정 변경 토큰이 필요합니다.',
    ROLE_ADMIN_REQUIRED: '설정 변경은 관리자 권한이 필요합니다.',
    CONFIGURATION_LOCKED: '유지보수 상태에서만 설정을 변경할 수 있습니다.',
    SITE_NOT_IN_MAINTENANCE: '유지보수 상태에서만 바꿀 수 있습니다. 시스템 옵션에서 운전 상태를 유지보수로 바꾸세요.',
    // Changing or removing the ground that open work stands on.
    ORDERS_STILL_OPEN: '진행 중인 반송 작업이 있습니다. 작업이 끝나거나 취소된 뒤에 가능합니다.',
    MOVES_STILL_OPEN: '끝나지 않은 이동이 있습니다. 이동이 끝난 뒤에 가능합니다.',
    ROADS_STILL_GRANTED: '아직 달리고 있는 차량이 있습니다 — 받은 구간 끝에 도착하지 않았거나, 두 노드 사이에 있거나, 위치를 보고하지 않은 차량입니다. 서 있는 차량이 자기 자리를 점유하는 것은 막지 않습니다.',
    REVISION_NOT_FOUND: '그런 지도 버전이 없습니다.',
    REVISION_IN_USE: '사용 중인 지도는 지울 수 없습니다. 다른 버전을 먼저 사용하도록 바꾸세요.',
    REVISION_HAS_HISTORY: '이 지도 위에서 수행된 기록(작업·이동·점유·위치 보고)이 있어 지우지 않습니다.',
    REVISION_HAS_NO_TRACK: '노드가 없는 지도는 사용할 수 없습니다.',
    REVISION_HAS_NO_VEHICLE: '차량이 없는 지도는 사용할 수 없습니다.',
    // Removing a piece of track something still points at.
    NODE_HAS_SEGMENTS: '이 노드에 닿는 구간이 있습니다. 구간을 먼저 지우세요.',
    NODE_HAS_PORT: '이 노드에 포트가 있습니다. 포트를 먼저 지우세요.',
    NODE_IN_VEHICLE_SETTINGS: '이 노드를 초기·대기·충전 위치로 쓰는 차량이 있습니다.',
    NODE_IN_ZONE: '이 노드가 Zone에 속해 있습니다.',
    NODE_IN_USE: '차량이 점유 중인 노드입니다.',
    NODE_CODE_IN_USE: '같은 코드의 노드가 이미 있습니다.',
    SEGMENT_ALREADY_EXISTS: '같은 방향의 구간이 이미 있습니다.',
    SEGMENT_ENDS_AT_ITS_START: '시작과 끝이 같은 구간은 만들 수 없습니다.',
    SEGMENT_IN_USE: '구간의 양 끝 중 하나를 차량이 점유 중입니다.',
    // A map file that does not hang together.
    MAP_HAS_NO_TRACK: '노드가 없는 지도입니다.',
    NODE_CODE_REPEATED: '같은 노드 코드가 두 번 나옵니다.',
    NODE_POSITION_REQUIRED: '노드마다 xMm·yMm이 필요합니다.',
    WAIT_FOR_INVALID: '대기 위치의 차종은 OHT 또는 AGV입니다.',
    CHARGE_POINT_NOT_A_WAIT_PLACE: '충전 지점은 대기 위치로 쓸 수 없습니다.',
    SEGMENT_NODE_UNKNOWN: '구간이 가리키는 노드가 파일 안에 없습니다.',
    SEGMENT_REPEATED: '같은 (시작, 끝) 구간이 두 번 나옵니다.',
    PORT_CODE_REPEATED: '같은 포트 코드가 두 번 나옵니다.',
    PORT_NODE_UNKNOWN: '포트가 가리키는 노드가 파일 안에 없습니다.',
    PORT_VEHICLE_TYPE_INVALID: '포트의 접근 차종은 OHT 또는 AGV입니다.',
    PORT_EQUIPMENT_UNKNOWN: '포트가 가리키는 설비가 이 사이트에 없습니다. 설비를 먼저 등록한 뒤 지도를 올려 주세요.',
    VEHICLE_CODE_REPEATED: '같은 차량 코드가 두 번 나옵니다.',
    VEHICLE_TYPE_INVALID: '차종은 OHT 또는 AGV입니다.',
    VEHICLE_NODE_UNKNOWN: '차량이 가리키는 노드가 파일 안에 없습니다.',
    CHARGING_NEEDS_A_CHARGE_NODE: '충전 정책을 쓰는 차량은 충전 자리(charge)를 지정해야 합니다.',
    ZONE_CODE_REPEATED: '같은 Zone 코드가 두 번 나옵니다.',
    ZONE_CAPACITY_INVALID: 'Zone 정원은 1~100입니다.',
    ZONE_KIND_INVALID: 'Zone 종류는 TRAFFIC 또는 MAINTENANCE입니다.',
    ZONE_NODE_UNKNOWN: 'Zone이 가리키는 노드가 파일 안에 없습니다.',
    ZONE_NODE_IN_TWO_ZONES: '한 노드가 두 Zone에 들어 있습니다.',
    VERSION_CONFLICT: '다른 곳에서 먼저 수정되었습니다. 최신 값을 다시 불러와 비교해 주세요.',
    VDA_PROFILE_NOT_APPLICABLE: '통신 방식이 MQTT가 아니면 VDA 프로파일은 공란이어야 합니다.',
    VDA_PROFILE_REQUIRED: 'MQTT 사이트에서 사용하는 차량은 VDA 프로파일이 필요합니다.',
    VDA_PROFILE_NOT_FOUND: '선택한 VDA 프로파일이 없습니다.',
    VDA_PROFILE_ASSIGNED: 'VDA 프로파일이 지정된 차량이 있어 MQTT에서 바꿀 수 없습니다. 차량의 프로파일을 먼저 비워 주세요.',
    VDA_ACTIONS_MUST_DIFFER: '출발지 동작과 목적지 동작은 달라야 합니다.',
    INVALID_NODE_COUNT: '허가 노드 수는 1~100 사이여야 합니다.',
    INVALID_MORE_TYPE: 'MORE 판정 방식은 NODE 또는 DISTANCE입니다.',
    INVALID_MORE_REMAINING_NODES: 'MORE 남은 노드 수는 1~99 사이여야 합니다.',
    MORE_NODES_NOT_BELOW_NODE_COUNT: 'MORE 남은 노드 수는 허가 노드 수보다 작아야 합니다.',
    INVALID_MORE_REMAINING_PERCENT: 'MORE 남은 거리 비율은 1~99% 사이여야 합니다.',
    INVALID_MORE_MIN_REMAINING_NODES: 'MORE 연장 하한은 0~99 사이여야 합니다.',
    MORE_MIN_ABOVE_TRIGGER: 'MORE 연장 하한은 남은 노드 수보다 클 수 없습니다.',
    INVALID_MERGE_CONTROL: '합류 판단은 CONTROLLER 또는 VEHICLE_SIGNAL입니다.',
    INVALID_TRANSFER_MAX_ATTEMPTS: '이적재 재시도 상한은 1~10 사이여야 합니다.',
    INVALID_YIELD_AFTER_SECONDS: '회피 대기 시간은 0~600초여야 합니다.',
    INVALID_HOME_RETURN_SECONDS: '홈 복귀 대기 시간은 0~3600초여야 합니다.',
    INVALID_CHARGE_LEVELS: '충전 기준은 위험 < 요청 < 해제 ≤ 100 이어야 합니다.',
    INVALID_RUN_MODE: '운전 모드는 SIMULATION 또는 OPERATION입니다.',
    // A mapping document: the dialect one family of vehicles is spoken to in.
    MAPPING_NOT_JSON: 'JSON 형식이 아닙니다. 파일 내용을 확인해 주세요.',
    MAPPING_NOT_FOUND: '이 사이트에 그런 매핑 문서가 없습니다.',
    MAPPING_IN_USE: '이 매핑을 쓰는 차량이나 설비가 있습니다. 그 통신 설정이 다른 프로토콜 프로파일을 가리키게 먼저 바꾸세요.',
    EQUIPMENT_ADAPTER_REQUIRED: '설비는 EQUIPMENT_WORD_V1 매핑 문서를 가리키는 프로토콜 프로파일이 필요합니다.',
    EQUIPMENT_NEEDS_A_DIALLED_LINK: '설비는 주기적으로 읽어야 하므로 접속하는 쪽(CLIENT) 연결만 쓸 수 있습니다.',
    EQUIPMENT_COMMUNICATION_NOT_FOUND: '그런 설비 통신 설정이 없습니다.',
  };
  return map[code] ?? code;
}
