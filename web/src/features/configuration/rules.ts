import type { ConnectionSettings, OperationOptions, ProtocolSettings, VdaProfileSettings, VehicleSettings } from '../../api';

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
export const ADAPTER_DISABLED_REASON = '장비 어댑터가 아직 구현되지 않아 활성화할 수 없습니다. 어댑터 연결 작업 이후에 열립니다.';

/** Server rejection codes rendered as sentences the operator can act on. */
export function explainCode(code: string): string {
  const map: Record<string, string> = {
    OHT_CHARGING_NOT_SUPPORTED: 'OHT는 충전을 사용할 수 없습니다. 충전 정책을 DISABLED로 두세요.',
    CHARGE_NODE_REQUIRED: '충전 정책을 사용하면 충전 노드를 지정해야 합니다.',
    INVALID_CONNECTION_ROLE: '선택한 통신 방식에 맞지 않는 역할입니다.',
    UNSUPPORTED_VDA_VERSION: '지원하지 않는 VDA5050 버전입니다.',
    ADAPTER_NOT_IMPLEMENTED: ADAPTER_DISABLED_REASON,
    CONFIGURATION_WRITES_DISABLED: '서버에서 설정 변경이 비활성화되어 있습니다.',
    PRINCIPAL_REQUIRED: '설정 변경 토큰이 필요합니다.',
    ROLE_ADMIN_REQUIRED: '설정 변경은 관리자 권한이 필요합니다.',
    CONFIGURATION_LOCKED: '유지보수 상태에서만 설정을 변경할 수 있습니다.',
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
    INVALID_RUN_MODE: '운전 모드는 SIMULATION 또는 OPERATION입니다.',
    SITE_NOT_IN_MAINTENANCE: '유지보수 상태에서만 설정을 변경할 수 있습니다.',
  };
  return map[code] ?? code;
}
