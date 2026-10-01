/**
 * stopNode: a movement authority ends here and is never renewed past it, so the vehicle halts.
 * mergePoint: two lines join here and the controller lets the vehicle that asked first go first.
 */
export type Node = {
  id: number; code: string; x: number; y: number; enabled: boolean;
  stopNode?: boolean; mergePoint?: boolean;
};
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
/**
 * Site-wide options. Every field changes behaviour that exists: the communication type applies to
 * every vehicle, nodeCount bounds one movement authority, MORE decides when a running authority
 * is lengthened, the command values decide how a provably failed send is retried, and the
 * staleness threshold decides when a position that stopped arriving stops being called current.
 */
export type OperationOptions = {
  commType: 'PC' | 'PLC' | 'MQTT'; nodeCount: number;
  moreEnabled: boolean; moreType: 'NODE' | 'DISTANCE'; moreRemainingNodes: number; moreRemainingPercent: number;
  autoDispatch: boolean; commandMaxAttempts: number; commandRetrySeconds: number; positionStaleSeconds: number;
  /** Below this many nodes left before the end of the route, the vehicle stops instead of being renewed. */
  moreMinRemainingNodes: number;
  /** When on, a renewal is sent only while the vehicle says it can accept a changed destination. */
  moreVehicleSignal: boolean;
  /** Who decides who goes first at a merge point: this controller, or the vehicle (signal) later on. */
  mergeControl: 'CONTROLLER' | 'VEHICLE_SIGNAL';
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
  /** Site-wide options: not one machine's parameters, so they sit apart and need ADMIN. */
  system: () => `/api/v1/sites/${SITE_ID}/configuration/system`,
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
      // Header values must be ISO-8859-1, so a Korean reason travels percent-encoded as UTF-8.
      ...(reason ? { 'X-Amhs-Reason': encodeURIComponent(reason) } : {}),
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
  /** Null until something reports, which is different from having reported a while ago. */
  reportedAt: string | null;
  /** What the vehicle last said about accepting a new destination; null when it never said. */
  destinationChangeEnabled?: boolean | null;
  /** Configured home node; a vehicle leaving a zone goes there first when it can. */
  homeNodeId?: number | null;
};

/** How current a reported position is, against the site's configured threshold. */
export function freshness(v: VehicleSnapshot, staleAfterMs: number):
  { label: string; tone: string } {
  if (v.source === 'INITIAL_CONFIGURATION' || !v.reportedAt) return { label: '보고 없음', tone: 'id' };
  const age = Date.now() - new Date(v.reportedAt).getTime();
  return age > staleAfterMs
    ? { label: `보고 지연 ${Math.round(age / 1000)}초`, tone: 'wr' }
    : { label: '최근 보고', tone: 'ok' };
}
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
/** A set of nodes that admits at most `capacity` vehicles; vehicleIds are those counted inside it now. */
export type ZoneOccupancy = {
  id: number; revisionId: number; code: string; capacity: number; enabled: boolean;
  nodeIds: number[]; vehicleIds: number[];
};

export type CommandStatus = 'PENDING' | 'DISPATCHING' | 'SENT' | 'ACCEPTED' | 'FAILED' | 'WITHDRAWN';
/** orderId is null for a movement that belongs to no order, such as leaving a zone after the work is done. */
export type DeviceCommand = {
  id: number; orderId: number | null; commandId: string; commandType: string; payload: string;
  status: CommandStatus; attempt: number;
  lastOutcome: string | null; lastError: string | null;
  createdAt: string; publishedAt: string | null; settledAt: string | null;
  /** The vehicle it moves. Commands for one vehicle leave one at a time, in the order written. */
  vehicleId?: number | null;
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
  zones: () => `/api/v1/sites/${SITE_ID}/traffic/zones`,
  inDoubt: (limit = 50) => `/api/v1/sites/${SITE_ID}/device-commands/in-doubt?limit=${limit}`,
  orderCommands: (orderId: number) => `/api/v1/sites/${SITE_ID}/transport-orders/${orderId}/device-commands`,
  orders: () => `/api/v1/sites/${SITE_ID}/transport-orders`,
  assign: (orderId: number) => `/api/v1/sites/${SITE_ID}/transport-orders/${orderId}/assignment`,
  cancel: (orderId: number) => `/api/v1/sites/${SITE_ID}/transport-orders/${orderId}/cancel`,
  recovery: (orderId: number) => `/api/v1/sites/${SITE_ID}/transport-orders/${orderId}/recovery`,
  extendAll: () => `/api/v1/sites/${SITE_ID}/movement-authority`,
  publish: () => `/api/v1/sites/${SITE_ID}/device-commands/publish`,
  sweep: () => `/api/v1/sites/${SITE_ID}/operation-sweep`,
  nodes: (revisionId: number) => `/api/v1/sites/${SITE_ID}/configuration/topology/${revisionId}/nodes`,
  segments: (revisionId: number) => `/api/v1/sites/${SITE_ID}/configuration/topology/${revisionId}/segments`,
  equipment: () => `/api/v1/sites/${SITE_ID}/configuration/equipment`,
  racks: (unitId: number) => `/api/v1/sites/${SITE_ID}/configuration/equipment/${unitId}/racks`,
  slots: (unitId: number, rackId: number) =>
    `/api/v1/sites/${SITE_ID}/configuration/equipment/${unitId}/racks/${rackId}/slots`,
  assignments: () => `/api/v1/sites/${SITE_ID}/transport-orders/assignments`,
  session: () => `/api/v1/session`,
};

/** A node's editable parameters. Its revision and the segments reaching it are structure, not these. */
export type NodeSettings = {
  code: string; xMm: number; yMm: number; enabled: boolean; stopNode: boolean; mergePoint: boolean;
};
/** A segment's editable parameters. Endpoints are what the segment is, so they are read-only here. */
export type EdgeSettings = { fromNodeId: number; toNodeId: number; cost: number; enabled: boolean };

export type EquipmentKind = 'STOCKER' | 'BUFFER' | 'PROCESS';
export type EquipmentSettings = { revisionId: number; code: string; name: string; kind: EquipmentKind; enabled: boolean };
export type RackSettings = { code: string; levels: number; columns: number; enabled: boolean };
/**
 * One cell. `usable` says somebody decided it is not to be filled — it is not occupancy, not a
 * reservation, and not where a carrier is. Showing those as one turns a blocked cell into a full one.
 */
export type SlotView = {
  id: number; rackId: number; level: number; col: number; code: string;
  usable: boolean; unusableReason: string | null;
};

/** Authority levels, lowest first. A screen offers what the session says it may do. */
export type Role = 'VIEWER' | 'OPERATOR' | 'ENGINEER' | 'ADMIN';
/** `allows` names the areas this session may change, as the server itself computes them. */
export type Session = { authenticated: boolean; code: string; role: Role; allows: string[] };
export const ROLE_LABEL: Record<Role, string> = {
  VIEWER: '조회', OPERATOR: '운전', ENGINEER: '설비 설정', ADMIN: '관리자',
};

export type Publication = { claimed: number; delivered: number; notDelivered: number; inDoubt: number };
export type SweepResult = {
  autoDispatch: boolean; assigned: number[]; skipped: { orderId: number; reason: string }[];
  /** Vehicles that had halted at the end of their authority and were sent the next stretch. */
  resumed: Extension[];
  /** Idle vehicles with no order being moved out of a zone, and why each one was or was not. */
  evacuated: { vehicleId: number; decision: string; addedNodeIds: number[]; commandId: string | null }[];
  extended: Extension[]; published: Publication;
};

/**
 * A read that identifies the caller, used to ask what this session may do. The token is sent
 * and never stored or rendered back; an unauthenticated call is answered as a viewer.
 */
export async function getAs<T>(url: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(url, { signal, headers: writeToken ? { Authorization: `Bearer ${writeToken}` } : {} });
  if (!res.ok) throw new ApiError(res.status, url, describe(res.status));
  return res.json() as Promise<T>;
}

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
    ZONE_FULL: '진입하려는 Zone의 정원이 찼습니다. 본선을 한 바퀴 돌아 다시 진입을 시도합니다.',
    MERGE_EARLIER_REQUEST: '합류 지점에서 먼저 요청한 차량이 있어 그 차량이 먼저 지나갑니다.',
    STOP_NODE: '정지 노드에서 허가가 끝났습니다. 차량이 멈춘 뒤 다음 구간을 보냅니다.',
    STOP_NODE_AHEAD: '허가 끝이 정지 노드라 연장하지 않고 정지시킵니다.',
    BELOW_MORE_FLOOR: '남은 경로가 연장 하한보다 짧아 연장하지 않고 정지시킵니다.',
    VEHICLE_NOT_ACCEPTING_CHANGE: '차량이 목적지 변경 가능 신호를 주지 않아 연장을 보내지 않았습니다.',
    RESUMED: '허가 끝에서 멈춘 차량에 다음 구간 이동을 보냈습니다.',
    VEHICLE_MOVING: '차량이 아직 움직이고 있어 새 이동 명령을 보내지 않았습니다.',
    COMMAND_OUTSTANDING: '앞선 이동 명령의 결과를 아직 몰라 새 명령을 보내지 않았습니다.',
    AUTHORITY_NOT_USED: '받은 허가를 아직 다 쓰지 않았습니다.',
    AT_ROUTE_END: '이미 경로 끝에 있습니다.',
    AT_PICKUP: '출발지에 도착했습니다. 적재 완료가 보고되면 목적지 구간을 보냅니다.',
    AUTHORIZED_TO_PICKUP: '출발지까지는 이미 확보했습니다. 목적지 구간은 적재 완료 보고 뒤에 받습니다.',
    ORDER_NOT_MOVABLE: '작업이 이동 단계가 아닙니다(적재·하역·취소 요청·복구 대기). 이동을 더 주지 않습니다.',
    POSITION_STALE: '차량 상태 보고가 끊긴 지 오래돼 그 상태로는 이동을 주지 않습니다.',
    NODE_OCCUPIED_BY_OTHER_VEHICLE: '다음 노드에 다른 차량이 서 있다고 보고했습니다. 허가가 없어도 실제 위치가 우선합니다.',
    VEHICLE_STATE_STALE: '배차할 수 있는 차량의 상태 보고가 모두 오래됐습니다. 최근 보고가 있어야 배차합니다.',
    VEHICLE_MOVED: '경로를 계산한 뒤 차량 위치가 바뀌었습니다. 다시 배차해 주세요.',
    VEHICLE_NO_LONGER_ELIGIBLE: '차량을 잡기 직전에 상태가 바뀌어 배차하지 않았습니다.',
    RECOVERY_EVIDENCE_REQUIRED: '화물 위치를 먼저 확인해 기록해야 복구를 끝낼 수 있습니다.',
    RECOVERY_LOCATION_MISMATCH: '기록된 화물 위치와 끝내려는 방식이 맞지 않습니다.',
    RECOVERY_LOCATION_REQUIRED: '화물이 출발지에 있는지 목적지에 있는지 지정해야 합니다.',
    RECOVERY_REASON_REQUIRED: '화물 위치를 어떻게 확인했는지 적어야 합니다.',
    ORDER_NOT_IN_RECOVERY: '복구 대기 중인 작업이 아닙니다.',
    EVACUATING: '작업을 마치고 Zone 안에 남은 차량을 Zone 밖으로 보냈습니다.',
    ARRIVED: 'Zone 밖 목적지에 도착해 이동을 마쳤습니다.',
    HAS_ORDER: '작업을 가진 차량은 그 작업으로 Zone을 나갑니다.',
    NO_PLACE_OUTSIDE: 'Zone 밖에 비어 있는 갈 곳이 없습니다.',
    NO_PATH: '목적지까지 경로가 없어 이동을 멈췄습니다.',
    NO_AVAILABLE_VEHICLE: '조건에 맞는 차량이 없습니다.',
    APPROACH_ROUTE_START_UNKNOWN: '차량 위치를 몰라 인수 지점까지 경로를 낼 수 없습니다.',
    APPROACH_ROUTE_NO_PATH: '모든 차량이 인수 지점에 도달할 수 없습니다.',
    DELIVERY_ROUTE_NO_PATH: '인수 지점에서 목적지까지 경로가 없습니다.',
  };
  return map[decision] ?? decision;
}
