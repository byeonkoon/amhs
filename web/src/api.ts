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
