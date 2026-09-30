export type Node = { id: number; code: string; x: number; y: number; enabled: boolean };
export type Edge = { id: number; fromNodeId: number; toNodeId: number; cost: number; enabled: boolean };
export type Port = { id: number; code: string; portType: string; accessVehicleType: 'OHT' | 'AGV'; linkedNodeId: number };
export type MapData = { siteId: number; revisionId: number; nodes: Node[]; edges: Edge[]; ports: Port[] };
export type Vehicle = {
  id: number; code: string; vType: 'OHT' | 'AGV'; currentNodeId: number;
  enabled: boolean; assignmentEnabled: boolean; state: string;
  /** INITIAL_CONFIGURATION means the position is configuration, not a live report. */
  positionSource: string; lastReceivedAt?: string | null;
};
export type TransportOrder = {
  id: number; status: string; sourcePortId: number; destinationPortId: number;
  vehicleType: 'OHT' | 'AGV'; carrierCode?: string | null; priority: number;
  createdAt: string; startedAt?: string | null; terminalAt?: string | null;
};

/** Thrown with the HTTP status so screens can explain 404 and 503 differently. */
export class ApiError extends Error {
  constructor(readonly status: number, readonly url: string, message: string) { super(message); }
}

export async function get<T>(url: string, signal: AbortSignal): Promise<T> {
  let res: Response;
  try {
    res = await fetch(url, { signal });
  } catch (cause) {
    if ((cause as Error).name === 'AbortError') throw cause;
    throw new ApiError(0, url, '서버에 연결하지 못했습니다.');
  }
  if (!res.ok) throw new ApiError(res.status, url, describe(res.status));
  return res.json() as Promise<T>;
}

function describe(status: number): string {
  if (status === 404) return '요청한 자원이 없습니다.';
  if (status === 401 || status === 403) return '이 작업을 수행할 권한이 없습니다.';
  if (status === 503) return '서버가 아직 요청을 받을 수 없습니다.';
  if (status >= 500) return '서버에서 오류가 발생했습니다.';
  return '요청을 처리하지 못했습니다.';
}

export const SITE_ID = 1;
export const api = {
  map: (revisionId: number) => `/api/v1/sites/${SITE_ID}/maps/${revisionId}`,
  vehicles: (revisionId?: number) => `/api/v1/sites/${SITE_ID}/vehicles${revisionId === undefined ? '' : `?revisionId=${revisionId}`}`,
  health: () => '/actuator/health',
  transportOrders: (limit = 20) => `/api/v1/sites/${SITE_ID}/transport-orders?limit=${limit}`,
};

// ---- 설정 (A19 계약) ----
export type Cfg<T> = { id: number; version: number; settings: T };
export type VehicleSettings = {
  revisionId: number; code: string; name: string; vType: 'OHT' | 'AGV';
  widthMm: number; lengthMm: number; heightMm: number;
  initialNodeId: number; homeNodeId?: number | null; chargeNodeId?: number | null;
  enabled: boolean; assignmentEnabled: boolean;
  controlAuthority: 'CONTROLLER_MANAGED' | 'VENDOR_MANAGED';
  bcrPolicy: 'DISABLED' | 'REQUIRED';
  chargingPolicy: 'DISABLED' | 'HOME' | 'NEAREST';
  /** Empty unless the site communication type is MQTT. */
  vdaProfileId?: number | null;
};
export type PortSettings = {
  revisionId: number; code: string; portType: string;
  accessVehicleType: 'OHT' | 'AGV'; linkedNodeId: number;
};
export type ConnectionSettings = { commType: 'TCP' | 'MQTT'; role: string; host: string; port: number; enabled: boolean };
export type ProtocolSettings = { protoType: 'PC' | 'PLC' | 'VDA5050'; protoVersion: string; adapterKey: string };
/** Site-wide option: one communication type for every vehicle, and the node limit of one movement authority. */
export type OperationOptions = {
  commType: 'PC' | 'PLC' | 'MQTT'; nodeCount: number;
  /** MORE extends a running authority: NODE by remaining nodes, DISTANCE by remaining share of the authorized distance. */
  moreEnabled: boolean; moreType: 'NODE' | 'DISTANCE'; moreRemainingNodes: number; moreRemainingPercent: number;
};
/** Topic version and JSON version are separate values; actions are sent at the pickup and drop ports. */
export type VdaProfileSettings = {
  code: string; interfaceName: string; topicVersion: string; manufacturer: string;
  jsonVersion: string; sourceAction: string; destAction: string;
};

export const cfgApi = {
  vehicles: () => `/api/v1/sites/${SITE_ID}/configuration/vehicles`,
  ports: () => `/api/v1/sites/${SITE_ID}/configuration/ports`,
  connections: () => `/api/v1/sites/${SITE_ID}/configuration/connections`,
  protocols: () => `/api/v1/sites/${SITE_ID}/configuration/protocols`,
  operation: () => `/api/v1/sites/${SITE_ID}/configuration/operation`,
  vdaProfiles: () => `/api/v1/sites/${SITE_ID}/configuration/vda-profiles`,
};

/** Write token is kept in memory only: it is never stored and never rendered back. */
let writeToken: string | null = null;
export const setWriteToken = (v: string | null) => { writeToken = v && v.trim() ? v.trim() : null; };
export const hasWriteToken = () => writeToken !== null;

export async function send<T>(url: string, method: 'POST' | 'PUT', body: unknown, reason?: string): Promise<T> {
  if (!writeToken) throw new ApiError(401, url, '설정 변경 토큰이 없습니다. 먼저 토큰을 입력해 주세요.');
  const res = await fetch(url, {
    method,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${writeToken}`,
      ...(reason ? { 'X-Amhs-Reason': reason } : {}),
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    let code = '';
    try { code = ((await res.json()) as { code?: string }).code ?? ''; } catch { /* body may be empty */ }
    throw new ApiError(res.status, url, code || `요청이 거부되었습니다 (HTTP ${res.status}).`);
  }
  return res.json() as Promise<T>;
}

// ---- 운영 (배차 · 예약 · 이동 허가 · 장비 명령) ----

/** Reported vehicle state. nodeId null means nobody has said where it is, which is not a position. */
export type VehicleSnapshot = {
  id: number; code: string; vType: 'OHT' | 'AGV'; nodeId: number | null;
  enabled: boolean; assignmentEnabled: boolean;
  online: boolean; remote: boolean; idle: boolean; carrierPresent: boolean;
  /** INITIAL_CONFIGURATION is configuration, SIMULATED is entered by hand, DEVICE came from a vehicle. */
  source: 'INITIAL_CONFIGURATION' | 'SIMULATED' | 'DEVICE';
};
export type VehicleReportInput = {
  nodeId: number | null; online: boolean; remote: boolean; idle: boolean;
  carrierPresent: boolean; source: string;
};
/** What a report changed in traffic: authorized false means the vehicle reported a node it does not hold. */
export type TrafficProgress = { vehicleId: number; nodeId: number; authorized: boolean; released: number; stillHeld: number };
export type VehicleReportResult = { vehicle: VehicleSnapshot; traffic?: TrafficProgress };

/** One node a vehicle may occupy. sequence orders the run it was granted. */
export type NodeHold = {
  id: number; siteId: number; revisionId: number; nodeId: number;
  vehicleId: number; orderId: number | null; sequence: number; grantedAt: string;
};
/** A closed chain of vehicles, each waiting for a node the next one holds. */
export type Deadlock = { vehicleIds: number[]; wantedNodeIds: number[] };

export type CommandStatus = 'PENDING' | 'DISPATCHING' | 'SENT' | 'ACCEPTED' | 'FAILED';
export type DeviceCommand = {
  id: number; orderId: number; commandId: string; commandType: string; payload: string;
  status: CommandStatus; attempt: number;
  lastOutcome: string | null; lastError: string | null;
  createdAt: string; publishedAt: string | null; settledAt: string | null;
};

export type AssignmentView = {
  id: number; orderId: number; vehicleId: number;
  assignedAt: string; releasedAt: string | null; releaseReason: string | null;
};
/** commandId null means the vehicle is held but traffic granted nothing, so it cannot start yet. */
export type Assigned = { order: TransportOrder; assignment: AssignmentView; commandId: string | null; created: boolean };
/** decision says why nothing was extended when addedNodeIds is empty. */
export type Extension = { orderId: number; vehicleId: number; decision: string; addedNodeIds: number[]; commandId: string | null };

export const opsApi = {
  vehicleState: (revisionId: number) => `/api/v1/sites/${SITE_ID}/maps/${revisionId}/vehicle-state`,
  reportVehicle: (revisionId: number, vehicleId: number) =>
    `/api/v1/sites/${SITE_ID}/maps/${revisionId}/vehicle-state/${vehicleId}`,
  reservations: (vehicleId?: number) =>
    `/api/v1/sites/${SITE_ID}/traffic/reservations${vehicleId === undefined ? '' : `?vehicleId=${vehicleId}`}`,
  deadlocks: () => `/api/v1/sites/${SITE_ID}/traffic/deadlocks`,
  inDoubt: (limit = 50) => `/api/v1/sites/${SITE_ID}/device-commands/in-doubt?limit=${limit}`,
  orderCommands: (orderId: number) => `/api/v1/sites/${SITE_ID}/transport-orders/${orderId}/device-commands`,
  orders: () => `/api/v1/sites/${SITE_ID}/transport-orders`,
  assign: (orderId: number) => `/api/v1/sites/${SITE_ID}/transport-orders/${orderId}/assignment`,
  cancel: (orderId: number) => `/api/v1/sites/${SITE_ID}/transport-orders/${orderId}/cancel`,
  extendAll: () => `/api/v1/sites//movement-authority`,
  publish: () => `/api/v1/sites/${SITE_ID}/device-commands/publish`,
};

/** Reasons the movement-authority sweep gives back, as sentences an operator can act on. */
export function explainDecision(decision: string): string {
  const map: Record<string, string> = {
    EXTENDED: '허가를 연장했습니다.',
    NOT_NEEDED: '아직 허가가 충분해 연장하지 않았습니다.',
    ROUTE_COMPLETE: '경로 끝까지 이미 확보해 연장할 것이 없습니다.',
    MORE_DISABLED: '연장 기능이 꺼져 있습니다.',
    MORE_OUTSTANDING: '앞선 연장 명령의 결과를 아직 모릅니다. 그것이 정리되기 전에는 보내지 않습니다.',
    VEHICLE_NOT_MOVING: '차량이 정지 상태입니다. 연장이 아니라 새 이동 명령이 필요합니다.',
    VEHICLE_NOT_CONTROLLABLE: '차량이 오프라인이거나 원격 제어 상태가 아닙니다.',
    VEHICLE_NOT_FOUND: '차량을 찾지 못했습니다.',
    POSITION_UNKNOWN: '차량 위치를 보고한 주체가 없습니다.',
    POSITION_OUTSIDE_AUTHORITY: '차량이 허가받지 않은 노드를 보고했습니다.',
    POSITION_OFF_ROUTE: '차량이 계획 경로 밖에 있습니다.',
    NO_ASSIGNMENT: '이 작업에 배정된 차량이 없습니다.',
    ORDER_FINISHED: '이미 끝난 작업입니다.',
    NOTHING_TO_ADD: '더 받을 구간이 없습니다.',
    NODE_HELD_BY_OTHER_VEHICLE: '다음 노드를 다른 차량이 점유하고 있습니다.',
    POSITION_HELD_BY_OTHER_VEHICLE: '차량이 선 노드를 다른 차량이 점유하고 있습니다. 점유 기록과 현장이 어긋납니다.',
    NODE_NOT_AVAILABLE: '다음 노드가 사용 금지 상태입니다.',
    NO_AVAILABLE_VEHICLE: '조건에 맞는 차량이 없습니다.',
    APPROACH_ROUTE_START_UNKNOWN: '차량 위치를 몰라 인수 지점까지 경로를 낼 수 없습니다.',
    APPROACH_ROUTE_NO_PATH: '모든 차량이 인수 지점에 도달할 수 없습니다.',
    DELIVERY_ROUTE_NO_PATH: '인수 지점에서 목적지까지 경로가 없습니다.',
  };
  return map[decision] ?? decision;
}
