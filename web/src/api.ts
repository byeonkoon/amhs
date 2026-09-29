export type Node = { id: number; code: string; x: number; y: number };
export type Edge = { id: number; fromNodeId: number; toNodeId: number; cost: number; enabled: boolean };
export type Port = { id: number; code: string; portType: string; makerType: string; accessVehicleType: 'OHT' | 'AGV'; linkedNodeId: number };
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
  vehicles: () => `/api/v1/sites/${SITE_ID}/vehicles`,
  health: () => '/actuator/health',
  transportOrders: (limit = 20) => `/api/v1/sites/${SITE_ID}/transport-orders?limit=${limit}`,
};
