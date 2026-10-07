/**
 * stopNode: a movement authority ends here and is never renewed past it, so the vehicle halts.
 * mergePoint: two lines join here and the controller lets the vehicle that asked first go first.
 */
export type Node = {
  id: number; code: string; x: number; y: number; enabled: boolean;
  stopNode?: boolean; mergePoint?: boolean;
  /** An AGV can charge here. */
  chargePoint?: boolean;
  /** The kind of vehicle that may stand here with nothing to do; null when none may. */
  waitFor?: 'AGV' | 'OHT' | null;
};
/** curveMm: how far the segment's middle lies off the straight line (see geometry.ts); 0 is straight. */
export type Edge = { id: number; fromNodeId: number; toNodeId: number; cost: number; enabled: boolean; curveMm?: number };
/** The equipment a port hands over to, when one is linked. */
export type Port = {
  id: number; code: string; portType: string; accessVehicleType: 'OHT' | 'AGV'; linkedNodeId: number;
  equipmentId?: number | null; equipmentCode?: string | null; equipmentName?: string | null;
};
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
  /** SOURCE, VEHICLE, DESTINATION or UNKNOWN, as established by evidence. */
  carrierLocation?: string;
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
export type SimulatedEquipmentState = {
  unitId: number; configuredEnabled: boolean; enabled: boolean; callEnabled: boolean; status: string;
};
export const simulationEquipmentApi = {
  list: () => `/api/v1/sites/${SITE_ID}/simulation/equipment`,
  state: (id: number) => `/api/v1/sites/${SITE_ID}/simulation/equipment/${id}/state`,
};
/**
 * One map revision as the server reports it, with how much it holds.
 *
 * <p>Which one is in use is not a screen setting: it is what the site is running on, and a screen
 * that assumes a number shows a different site from the one the vehicles are driving.
 */
export type MapRevision = {
  id: number; revision: number; status: 'DRAFT' | 'ACTIVE' | 'RETIRED';
  nodes: number; segments: number; ports: number; vehicles: number;
};
/**
 * The revision the site is running on, as last read from the server.
 *
 * <p>Held in one place, like the session token, because every screen needs it and none of them
 * should be the one that decides it. It starts at the first revision so a failed read shows a map
 * rather than nothing, and is replaced as soon as the server has answered.
 */
let activeRevision = 1;
export function revisionId(): number { return activeRevision; }
export function setRevisionId(id: number) { activeRevision = id; }

export const api = {
  map: (revisionId: number) => `/api/v1/sites/${SITE_ID}/maps/${revisionId}`,
  /** Every revision of this site's map, and which one is in use. A read, open like the rest. */
  mapRevisions: () => `/api/v1/sites/${SITE_ID}/map-revisions`,
  vehicles: (revisionId?: number) => `/api/v1/sites/${SITE_ID}/vehicles${revisionId === undefined ? '' : `?revisionId=${revisionId}`}`,
  health: () => '/actuator/health',
  transportOrders: (limit = 20) => `/api/v1/sites/${SITE_ID}/transport-orders?limit=${limit}`,
  /** Work still going on; finished orders are history. */
  activeOrders: (limit = 200) => `/api/v1/sites/${SITE_ID}/transport-orders?scope=active&limit=${limit}`,
  /** Transfer reports that arrived after their order ended and disagree with it; and closing one. */
  lateEvidence: () => `/api/v1/sites/${SITE_ID}/transport-orders/late-evidence`,
  resolveLateEvidence: (id: number) => `/api/v1/sites/${SITE_ID}/transport-orders/late-evidence/${id}/resolve`,
  finishedOrders: (limit = 200) => `/api/v1/sites/${SITE_ID}/transport-orders?scope=finished&limit=${limit}`,
  orderEvents: (orderId: number) => `/api/v1/sites/${SITE_ID}/transport-orders/${orderId}/events`,
  orderExplanation: (orderId: number) => `/api/v1/sites/${SITE_ID}/transport-orders/${orderId}/explanation`,
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
  /** Travel speed, mm/s. The simulator drives segments at this speed. */
  speedMmS?: number;
};
export type PortSettings = {
  revisionId: number; code: string; portType: string;
  accessVehicleType: 'OHT' | 'AGV'; linkedNodeId: number;
  equipmentUnitId?: number | null;
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
  /** How often a pickup or drop shown not to have happened is tried again before recovery. */
  transferMaxAttempts: number;
  /** SIMULATION: vehicles simulated in the server carry out the work. OPERATION: real devices. */
  runMode: 'SIMULATION' | 'OPERATION';
  /** Seconds a vehicle may wait for an idle one in its way before that one is moved aside; 0 never. */
  yieldAfterSeconds?: number;
  /** Seconds without work before a vehicle with a home node is sent home; 0 never. */
  homeReturnSeconds?: number;
  /** AGV charging thresholds, state of charge in percent. */
  chargeRequestPct?: number; chargeCriticalPct?: number; chargeReleasePct?: number;
  /**
   * For a vehicle fed from the rail, which has no charging cycle: how long its state of charge may
   * fall without once rising, and how far it must have fallen over that time, before it is called a
   * power-pickup fault. Either left unset leaves the rule off.
   */
  powerDropWindowSeconds?: number | null; powerDropMinPct?: number | null;
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
  /**
   * Whether the site is running or in maintenance. Configuration is refused while it runs, so this
   * is the one setting that decides whether any of the others may be written.
   */
  siteMode: () => `/api/v1/sites/${SITE_ID}/configuration/system/mode`,
  /** Puts a map revision into use. ADMIN, and the site has to be in maintenance. */
  activateRevision: (revisionId: number) =>
    `/api/v1/sites/${SITE_ID}/configuration/topology/${revisionId}/activate`,
  /** A whole map in as one document: it becomes a new draft revision and touches nothing in use. */
  importMap: () => `/api/v1/sites/${SITE_ID}/configuration/topology/import`,
  /** Removes a revision nothing uses and nothing remembers. Never the one in use. */
  deleteRevision: (revisionId: number) => `/api/v1/sites/${SITE_ID}/configuration/topology/${revisionId}`,
  /** The same document back out, so a map can be taken, edited and brought in again. */
  exportMap: (revisionId: number) =>
    `/api/v1/sites/${SITE_ID}/configuration/topology/${revisionId}/export`,
  vdaProfiles: () => `/api/v1/sites/${SITE_ID}/configuration/vda-profiles`,
  /** Who may do what. ADMIN only; a password goes in here and never comes back out. */
  accounts: () => `/api/v1/sites/${SITE_ID}/configuration/accounts`,
};

/**
 * An account as it may be shown. Nothing here carries the password or its stored form: `canLogIn`
 * says only that one has been set, which is all a listing needs in order to be useful.
 */
export type AccountView = {
  id: number; siteId: number; code: string; displayName: string; role: Role;
  enabled: boolean; canLogIn: boolean; level: number; version: number;
};
export type AccountSettings = { code: string; displayName: string; role: Role; enabled: boolean };
/** Settings plus the first password: the one field that goes in and never comes back. */
export type NewAccount = AccountSettings & { password: string };

/** The session is kept in memory only: it is never stored and never rendered back. */
let writeToken: string | null = null;
export const setWriteToken = (v: string | null) => { writeToken = v && v.trim() ? v.trim() : null; };
export const hasWriteToken = () => writeToken !== null;
/**
 * The level of the session the screens were last told about. A login alone is not leave to operate:
 * a level 1 login reads like anyone else, and a button it cannot use must not look usable.
 */
let sessionLevel = 0;
export const noteSessionLevel = (level: number) => { sessionLevel = level; };
/** Logged in at level 2 (operation) or above: what every operating control on the screens needs. */
export const canOperate = () => writeToken !== null && sessionLevel >= 2;

export async function send<T>(url: string, method: 'POST' | 'PUT' | 'DELETE', body: unknown, reason?: string,
  extraHeaders?: Record<string, string>): Promise<T> {
  if (!writeToken) throw new ApiError(401, url, '로그인이 필요합니다. 오른쪽 위 계정 메뉴에서 로그인해 주세요.');
  const res = await fetch(url, {
    method,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${writeToken}`,
      // Header values must be ISO-8859-1, so a Korean reason travels percent-encoded as UTF-8.
      ...(reason ? { 'X-Amhs-Reason': encodeURIComponent(reason) } : {}),
      ...(extraHeaders ?? {}),
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
  /** Who drives it: MANUAL, SEMI_AUTO or REMOTE. Null until something reports. */
  mode?: string | null;
  /** What it is doing: IDLE, MOVING, ERROR, PAUSED, CHARGING, LOADING, UNLOADING or RESET. */
  status?: string | null;
  /** Where it is while it drives between nodes; null while it stands on nodeId. */
  xMm?: number | null; yMm?: number | null;
  /** Configured charging node. */
  chargeNodeId?: number | null;
  /** Configured travel speed, mm/s. */
  speedMmS?: number;
  /** State of charge, percent, as last reported; null when it reports none. */
  batteryPct?: number | null;
  /** DISABLED, HOME (own charge node) or NEAREST (nearest free charge point). */
  chargingPolicy?: string | null;
  /**
   * Somebody has said this vehicle is not to be worked, with who and when. Different from the
   * configured "not for dispatch", which says how a vehicle is normally used; this says something
   * happened to this one. Taking it out stops it being given work; it does not move it.
   */
  outOfServiceSince?: string | null;
  outOfServiceReason?: string | null;
  outOfServiceBy?: string | null;
};

/** A move without an order. stopRequestedAt is set once a stop was asked and it is winding down. */
export type VehicleMove = {
  id: number; vehicleId: number; zoneId: number | null; targetNodeId: number; purpose: 'MANUAL' | 'ZONE_EVACUATION';
  startedAt: string; stopRequestedAt: string | null;
};

/** The vehicle's mode and status as the existing system names them. */
export const MODE_LABEL: Record<string, string> = { MANUAL: '수동', SEMI_AUTO: '반자동', REMOTE: '원격' };
export const STATUS_LABEL: Record<string, string> = {
  IDLE: '대기', MOVING: '이동', ERROR: '에러', PAUSED: '일시정지', CHARGING: '충전',
  LOADING: '적재', UNLOADING: '하역', RESET: '리셋',
};
export type OrderEvent = {
  seq: number; oldStatus: string | null; newStatus: string; reason: string | null; actorCode: string | null; occurredAt: string;
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
/** Where a vehicle with work or a move is going, and the nodes still ahead of it. */
export type VehicleHeading = { vehicleId: number; purpose: string; targetNodeId: number; remainingNodeIds: number[] };
export type NodeHold = {
  id: number; siteId: number; revisionId: number; nodeId: number;
  vehicleId: number; orderId: number | null; sequence: number; grantedAt: string;
};
/** A closed chain of vehicles, each waiting for a node the next one holds. */
export type Deadlock = { vehicleIds: number[]; wantedNodeIds: number[] };
/**
 * A deadlock with its links: vehicleId waits for wantedNodeId, which blockerId holds or stands on.
 * releasable: the blocker only holds it ahead of where it stands, so the blocker giving way frees it.
 */
export type DeadlockLink = { vehicleId: number; wantedNodeId: number; blockerId: number; blockerStandsThere: boolean; releasable: boolean };
export type DeadlockPlan = { vehicleIds: number[]; wantedNodeIds: number[]; links: DeadlockLink[]; recoveryId: number | null };
/** One vehicle giving way to break a deadlock. STOPPING → YIELDING → DONE, or ABANDONED by an operator. */
export type DeadlockRecovery = {
  id: number; vehicleId: number; beneficiaryVehicleId: number; wantedNodeId: number; chainVehicleIds: number[];
  status: 'STOPPING' | 'YIELDING' | 'DONE' | 'ABANDONED'; reason: string; requestedBy: string; requestedAt: string;
  stopCommandId: string | null; stopSettledAt: string | null; releasedNodeIds: number[]; releasedAt: string | null;
  finishedAt: string | null; finishReason: string | null;
};
/** A set of nodes that admits at most `capacity` vehicles; vehicleIds are those counted inside it now. */
/**
 * `kind` is the same shape with the opposite intent. A TRAFFIC zone is passed through and a vehicle
 * is moved out of it once its work there is done; a MAINTENANCE area is where a vehicle is put
 * because it should not be on the line, and it stays until somebody drives it out.
 */
export type ZoneOccupancy = {
  id: number; revisionId: number; code: string; capacity: number; enabled: boolean;
  kind: 'TRAFFIC' | 'MAINTENANCE';
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

/**
 * One thing the comparison after a server start found. blocking: confirmation is refused while it is
 * there. The vehicle, order, command and node it is about are given where they apply.
 */
export type RestartCheckItem = {
  kind: 'VEHICLE_SILENT' | 'VEHICLE_REPORT_STALE' | 'POSITION_UNKNOWN' | 'VEHICLE_OFF_ROAD' | 'COMMAND_IN_DOUBT'
    | 'CARRIER_UNEXPECTED' | 'VEHICLE_CONFIRMED' | 'VEHICLE_MOVING' | 'VEHICLE_ISOLATED' | 'COMMAND_UNSENT'
    | 'CARRIER_MISMATCH' | 'HANDOVER_INTERRUPTED' | 'ORDER_WAITS_FOR_OPERATOR';
  blocking: boolean; vehicleId: number | null; orderId: number | null; commandId: string | null;
  nodeId: number | null; detail: string | null;
};
export type RestartCheckState = {
  pending: boolean; checkId: number | null; since: string | null; items: RestartCheckItem[]; confirmable: boolean;
  last: {
    id: number; startedAt: string; finishedAt: string; outcome: 'NOTHING_IN_PROGRESS' | 'CONFIRMED';
    finishedBy: string | null; reason: string | null; releasedNodes: number | null; withdrawnCommands: number | null;
    settledCommands: number;
  } | null;
};

/** A carrier reported somewhere after its order ended, not yet dealt with by a person. */
export type LateEvidence = {
  /** AFTER_END: about an order already finished. CONFLICT: reports on an open order that contradict each other. */
  id: number; orderId: number; kind: 'AFTER_END' | 'CONFLICT'; orderStatus: string; reportedStatus: string; recordedLocation: string | null;
  reportedLocation: string; evidenceKind: string; commandId: string | null; receivedAt: string;
};

export const opsApi = {
  /** The comparison a site waits for after the server starts, its confirmation, and closing a command lost in the restart. */
  restartCheck: () => `/api/v1/sites/${SITE_ID}/restart-check`,
  restartCheckConfirm: () => `/api/v1/sites/${SITE_ID}/restart-check/confirm`,
  restartCheckClose: (commandId: string) => `/api/v1/sites/${SITE_ID}/restart-check/commands/${encodeURIComponent(commandId)}/close`,
  vehicleState: (revisionId: number) => `/api/v1/sites/${SITE_ID}/maps/${revisionId}/vehicle-state`,
  /** Takes a vehicle out of service, or puts it back. It does not move it; that is a separate act. */
  vehicleService: (revisionId: number, vehicleId: number) =>
    `/api/v1/sites/${SITE_ID}/maps/${revisionId}/vehicle-state/${vehicleId}/service`,
  reportVehicle: (revisionId: number, vehicleId: number) =>
    `/api/v1/sites/${SITE_ID}/maps/${revisionId}/vehicle-state/${vehicleId}`,
  reservations: (vehicleId?: number) =>
    `/api/v1/sites/${SITE_ID}/traffic/reservations${vehicleId === undefined ? '' : `?vehicleId=${vehicleId}`}`,
  deadlocks: () => `/api/v1/sites/${SITE_ID}/traffic/deadlocks`,
  deadlockPlans: () => `/api/v1/sites/${SITE_ID}/deadlock-plans`,
  deadlockRecoveries: () => `/api/v1/sites/${SITE_ID}/deadlock-recoveries`,
  closeRecovery: (id: number) => `/api/v1/sites/${SITE_ID}/deadlock-recoveries/${id}/close`,
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
  /** Changes only a simulated vehicle's mode and status; its position and load stay as they are. */
  simulatedState: (vehicleId: number) => `/api/v1/sites/${SITE_ID}/simulation/vehicles/${vehicleId}/state`,
  /**
   * Puts a simulated vehicle on a node outright. Not driving: everything it held is released, and
   * it is refused while an order or a move is running, because both store a route it would no
   * longer be on.
   */
  simulatedPlace: (vehicleId: number) => `/api/v1/sites/${SITE_ID}/simulation/vehicles/${vehicleId}/place`,
  /** Simulates a rail-powered vehicle failing to pick its power up, or picking it up again. */
  simulatedPickup: (vehicleId: number) => `/api/v1/sites/${SITE_ID}/simulation/vehicles/${vehicleId}/power-pickup`,
  simulatedPickupFaults: () => `/api/v1/sites/${SITE_ID}/simulation/power-pickup-faults`,
  /** An operator sends a vehicle to a node. */
  moveVehicle: (vehicleId: number) => `/api/v1/sites/${SITE_ID}/vehicles/${vehicleId}/move`,
  /** Stops an operator's move: nothing more is granted, and it closes once the vehicle has stopped. */
  stopMove: (vehicleId: number) => `/api/v1/sites/${SITE_ID}/vehicles/${vehicleId}/move/stop`,
  /**
   * Whether the fleet is running, and the switch for it. Not a configuration path: it is what
   * somebody reaches for when something is wrong on the floor, so it takes Lv2 운전.
   */
  execution: () => `/api/v1/sites/${SITE_ID}/execution`,
  /** An operator sends a vehicle to charge now, or stops a charge, whatever the thresholds say. */
  chargeVehicle: (vehicleId: number) => `/api/v1/sites/${SITE_ID}/vehicles/${vehicleId}/charge`,
  /** Open moves without an order: an operator's moves and moves out of a zone. */
  vehicleMoves: () => `/api/v1/sites/${SITE_ID}/vehicle-moves`,
  /** Destination and remaining route of every vehicle with work or a move. */
  vehicleHeadings: () => `/api/v1/sites/${SITE_ID}/vehicle-headings`,
  session: () => `/api/v1/session`,
  /** Logging in is how a session is got at all, so it is an operation and not a configuration change. */
  logIn: () => `/api/v1/sites/${SITE_ID}/session`,
  logOut: () => '/api/v1/session',
};

/** A node's editable parameters. Its revision and the segments reaching it are structure, not these. */
export type NodeSettings = {
  code: string; xMm: number; yMm: number; enabled: boolean; stopNode: boolean; mergePoint: boolean;
  chargePoint?: boolean;
  /** 'AGV' or 'OHT' when a vehicle of that kind may wait here; null when none may. */
  waitFor?: 'AGV' | 'OHT' | null;
};
/** A segment's editable parameters. Endpoints are what the segment is, so they are read-only here. */
export type EdgeSettings = { fromNodeId: number; toNodeId: number; cost: number; enabled: boolean; curveMm?: number };

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

// ---- 재고 (저장된 화물의 위치) ----

/**
 * Where one carrier is while nothing is carrying it. Exactly one of `slotId` and `portId` is set:
 * a cell is a place somebody looked at, and a port means it was handed over there with the cell
 * never recorded. The second is not a worse version of the first — it is a different thing to know.
 */
export type Placement = {
  id: number; siteId: number; carrierCode: string;
  slotId: number | null; portId: number | null;
  evidence: 'OPERATOR' | 'DEVICE'; reason: string; actorCode: string | null;
  placedAt: string; version: number;
  /** Labels so a list can name the place instead of printing an id nobody can look up. */
  slotCode: string | null; rackCode: string | null;
  unitCode: string | null; unitName: string | null; portCode: string | null;
};
/**
 * One cell with both of the independent things that can be true of it. `usable` is a decision
 * somebody recorded; `carrierCode` is what is in it now. A cell can be both unusable and holding
 * something, so these are never merged into one status.
 */
export type CellView = {
  slotId: number; level: number; col: number; code: string;
  usable: boolean; unusableReason: string | null;
  carrierCode: string | null; placedAt: string | null;
};
export type InventorySummary = {
  stored: number; inCells: number; atPortOnly: number; inTransport: number;
  cells: number; blockedCells: number;
};
/**
 * One rack, named by the equipment it belongs to: a place something can be put down. This is not
 * the equipment list — a process machine the controller only hands over to has no shelves, and
 * offering it here would open a screen about storage on somewhere nothing is stored.
 */
export type StoragePlace = {
  unitId: number; unitCode: string; unitName: string; kind: EquipmentKind;
  rackId: number; rackCode: string; levels: number; columns: number; enabled: boolean;
  cells: number; blockedCells: number; occupied: number;
};
export const invApi = {
  placements: () => `/api/v1/sites/${SITE_ID}/inventory/placements`,
  placement: (carrierCode: string) =>
    `/api/v1/sites/${SITE_ID}/inventory/placements/${encodeURIComponent(carrierCode)}`,
  summary: () => `/api/v1/sites/${SITE_ID}/inventory/summary`,
  places: () => `/api/v1/sites/${SITE_ID}/inventory/places`,
  cells: (unitId: number, rackId: number) =>
    `/api/v1/sites/${SITE_ID}/inventory/equipment/${unitId}/racks/${rackId}/cells`,
};

// ---- 개요 ----

/**
 * The site in one answer. Every number says what it counted, because a figure on an overview is
 * read as a fact about the site and almost never looked up afterwards.
 */
export type Overview = {
  operation: { mode: string; runMode: 'SIMULATION' | 'OPERATION'; autoDispatch: boolean };
  /** `notReporting` has never said where it is; `stale` said so and stopped. Only one is a fault. */
  fleet: { configured: number; usable: number; working: number; outOfService: number; notReporting: number; stale: number };
  /** `needingAPerson` is waiting on somebody, which is the only one that will not move on its own. */
  work: { queued: number; running: number; needingAPerson: number; oldestQueuedSeconds: number | null };
  /**
   * `medianSeconds` is the middle time from a job starting to it ending, over completed ones only;
   * `measured` says how many it is the middle of, so a median of two is visible as a median of two.
   */
  throughput: {
    windowMinutes: number; finished: number; completed: number; cancelled: number; failed: number;
    medianSeconds: number | null; measured: number;
  };
  alarms: AlarmCount;
  stored: InventorySummary;
  at: string;
};
export const overviewApi = {
  overview: (windowMinutes = 60) => `/api/v1/sites/${SITE_ID}/overview?windowMinutes=${windowMinutes}`,
};

// ---- 알람 (지금 사실인 조건) ----

/**
 * One thing that is true now and that somebody should do something about.
 *
 * `effect` is what makes it worth reading: what the condition is already causing. A line that says
 * only what is wrong leaves the reader to work out whether it matters, and a screen full of those
 * gets ignored. `since` is a real timestamp from the thing itself and `sinceMeans` says which one,
 * because "last reported at" and "started at" are different claims; null means nothing in the data
 * says when it began, which is itself worth seeing.
 */
export type Alarm = {
  key: string; severity: 'CRITICAL' | 'WARNING'; rule: string;
  subject: string; summary: string; effect: string;
  since: string | null; sinceMeans: string;
  /** The screen that can do something about it. */
  screen: MenuTarget;
  /**
   * That somebody has looked at this, and what they said. Null until anybody has. It does not
   * clear the alarm and cannot: the condition is still true, and a screen that lets a person
   * remove a true condition is how a real fault disappears.
   */
  seen: { reason: string; actorCode: string | null; at: string } | null;
  /** What it is about, as references: so a screen can go from the alarm to the vehicle, order or command. */
  about: { vehicleIds: number[]; orderId: number | null; commandId: string | null } | null;
};
export type MenuTarget = 'fleet' | 'transport' | 'traffic' | 'diagnostics' | 'inventory';
/** `unseen` is how many nobody has said they have looked at yet. */
export type AlarmCount = { total: number; critical: number; warning: number; unseen: number };
/** One stretch of time an alarm was seen true, with who looked and what it was about. Observed times. */
export type AlarmOccurrence = {
  id: number; key: string; severity: 'CRITICAL' | 'WARNING'; rule: string; subject: string; summary: string;
  vehicleIds: number[]; orderId: number | null; commandId: string | null; conditionSince: string | null;
  firstSeenAt: string; lastSeenAt: string; clearedAt: string | null;
  ackReason: string | null; ackActor: string | null; ackAt: string | null;
};
/** Why an order is where it is: the facts the order screen explains it from. */
export type OrderExplanation = {
  order: TransportOrder; vehicleId: number | null;
  dispatch: { reason: string; since: string | null } | null;
  roadWait: { vehicleId: number; wantedNodeId: number; blockerId: number; since: string } | null;
  unsettled: DeviceCommand[]; evidence: LateEvidence[]; transferAttempts: number;
};

export const alarmApi = {
  alarms: () => `/api/v1/sites/${SITE_ID}/alarms`,
  history: (filter: { vehicleId?: number; orderId?: number; limit?: number } = {}) => {
    const q = new URLSearchParams();
    if (filter.vehicleId != null) q.set('vehicleId', String(filter.vehicleId));
    if (filter.orderId != null) q.set('orderId', String(filter.orderId));
    q.set('limit', String(filter.limit ?? 100));
    return `/api/v1/sites/${SITE_ID}/alarms/history?${q}`;
  },
  count: () => `/api/v1/sites/${SITE_ID}/alarms/count`,
  seen: (alarmKey: string) => `/api/v1/sites/${SITE_ID}/alarms/${encodeURIComponent(alarmKey)}/seen`,
};

/** Authority levels, lowest first. A screen offers what the session says it may do. */
export type Role = 'VIEWER' | 'OPERATOR' | 'MANAGER' | 'ADMIN';
/** 1 to 4, the same numbers the login screen and the account list speak in. */
export const ROLE_LEVEL: Record<Role, number> = { VIEWER: 1, OPERATOR: 2, MANAGER: 3, ADMIN: 4 };
/** `allows` names the areas this session may change, as the server itself computes them. */
export type Session = { authenticated: boolean; code: string; role: Role; level: number; allows: string[] };
/** The session value is in the reply to a login and nowhere else; it is held in memory only. */
export type LoggedIn = { token: string; expiresAt: string; session: Session };
export const ROLE_LABEL: Record<Role, string> = {
  VIEWER: 'Lv1 조회', OPERATOR: 'Lv2 운전', MANAGER: 'Lv3 운영 관리', ADMIN: 'Lv4 관리자',
};
/** What each level reaches, written once so the screen and the gate cannot describe it differently. */
export const ROLE_SCOPE: Record<Role, string> = {
  VIEWER: '지도·차량, 알람, 이력, 진단 조회',
  OPERATOR: '위 전부 + 이동 조작, 반송 작업, 구간 점유',
  MANAGER: '위 전부 + 재고',
  ADMIN: '위 전부 + 자원 구성, 설비, 시스템 옵션, 계정',
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
 * A call that does not require being logged in — logging in is the obvious one, since it is how a
 * session is got in the first place. The current session is still sent when there is one, so
 * logging out can name the session it is ending.
 */
export async function post<T>(url: string, body?: unknown, method: 'POST' | 'DELETE' = 'POST'): Promise<T> {
  const res = await fetch(url, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(writeToken ? { Authorization: `Bearer ${writeToken}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    let code = '';
    try { code = ((await res.json()) as { code?: string }).code ?? ''; } catch { /* body may be empty */ }
    throw new ApiError(res.status, url, code || describe(res.status));
  }
  return res.json() as Promise<T>;
}

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
    MOVE_STOPPED: '이동을 중지했습니다. 차량이 선 자리에서 이동을 닫았습니다.',
    YIELDING: '다른 차량이 지나가도록 비켜 서는 중입니다.',
    CHARGE_STARTED: '충전을 시작했습니다.',
    CHARGE_STARTING: '충전 시작 명령을 보냈습니다. 차량이 충전 중이라고 보고하면 끝납니다.',
    CHARGE_STOPPING: '충전 중지를 요청했습니다. 차량이 충전을 멈췄다고 보고하면 끝납니다.',
    CHARGE_ENDED: '충전이 끝났습니다.',
    CHARGE_NOT_STARTED: '충전 시작 명령은 전달됐지만 차량이 충전 중이라고 보고하지 않았습니다.',
    CHARGE_COMPLETE: '충전을 마쳤습니다.',
    CHARGE_STOPPED_FOR_WORK: '대기 중인 작업이 있어 충전을 멈췄습니다.',
    CHARGE_WAITS_FOR_CARRIER: '화물을 싣고 있어 충전하러 가지 않습니다.',
    MOVE_ENDED_BY_STOP: '사이트를 정지해 제어기가 시작했던 이동을 닫았습니다. 다시 활성하면 그때 상황을 보고 새로 정합니다.',
    SEQUENCE_STOPPED: '사이트가 정지 상태입니다. 활성으로 바꾼 뒤에 가능합니다.',
    CHARGING_DISABLED: '이 차량은 스스로 충전하지 않습니다(레일 급전). 버퍼가 떨어지면 알람으로 알립니다.',
    ALREADY_CHARGING: '이미 충전 중입니다.',
    NOT_CHARGING: '충전 중이 아닙니다.',
    CARRIER_ON_BOARD: '화물을 싣고 있어 충전하러 보내지 않습니다. 하역이 끝난 뒤에 보내세요.',
    NO_FREE_CHARGER: '비어 있는 충전기가 없습니다.',
    NO_WAITING_PLACE: '충전이 끝났지만 비켜 설 대기 위치가 지정돼 있지 않아 충전기에 그대로 서 있습니다. 노드에 대기 위치를 지정해 주세요.',
    WAITING_PLACE_TAKEN: '충전이 끝났지만 대기 위치가 모두 차 있어 충전기에 그대로 서 있습니다.',
    CHARGER_HELD_BY_CARRIER: '화물을 실은 채 충전기에 서 있습니다. 하역 뒤에 비켜 세웁니다.',
    GOING_HOME: '작업이 없어 홈으로 가는 중입니다.',
    GOING_TO_CHARGE: '충전하러 가는 중입니다.',
    NO_PLACE_TO_YIELD: '비켜 설 빈 자리가 없습니다.',
    PORT_IN_USE: '출발 또는 도착 포트를 다른 작업이 쓰고 있어 기다립니다.',
    CARRIER_IN_TRANSPORT: '같은 화물 ID로 진행 중인 작업이 있습니다.',
    MOVE_STOPPED_STOPPING: '이동 중지를 요청했습니다. 더 허가하지 않으며, 이미 받은 허가 끝에서 차량이 서면 이동을 닫습니다.',
    NO_MOVE: '이 차량에는 진행 중인 이동이 없습니다.',
    NOT_A_MANUAL_MOVE: 'Zone 퇴출 이동은 여기서 중지하지 않습니다. 차량을 자동 작업에서 제외하세요.',
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
    PRINCIPAL_REQUIRED: '로그인이 필요하거나 로그인이 끝났습니다. 오른쪽 위 계정 메뉴에서 다시 로그인해 주세요.',
    ROLE_OPERATOR_REQUIRED: 'Lv2 운전 이상 권한이 필요합니다.',
    ROLE_MANAGER_REQUIRED: 'Lv3 운영 관리 이상 권한이 필요합니다.',
    ROLE_ADMIN_REQUIRED: 'Lv4 관리자 권한이 필요합니다.',
    LOGIN_REFUSED: '아이디나 비밀번호가 맞지 않습니다.',
    PASSWORD_TOO_SHORT: '비밀번호가 너무 짧습니다.',
    LAST_ADMIN: '마지막 관리자입니다. 내리면 권한을 되돌려 줄 사람이 남지 않습니다.',
    PICKUP_EVIDENCE_REQUIRED: '화물이 차량에 실렸다는 증거가 있어야 반송 단계로 넘어갑니다.',
    DROP_EVIDENCE_REQUIRED: '화물이 목적지에 내려졌다는 증거가 있어야 완료됩니다.',
    RETRY_EVIDENCE_REQUIRED: '이적재가 일어나지 않았다는 증거가 있어야 다시 시도합니다.',
    EVIDENCE_NEEDS_PRINCIPAL: '사람의 확인·결정은 로그인한 사용자만 기록할 수 있습니다.',
    EVACUATING: '작업을 마치고 Zone 안에 남은 차량을 Zone 밖으로 보냈습니다.',
    MOVING_TO_TARGET: '차량을 목표 노드로 보냈습니다.',
    VEHICLE_HAS_ORDER: '작업을 수행 중인 차량은 수동으로 이동할 수 없습니다.',
    VEHICLE_ALREADY_MOVING: '이미 이동 중인 수동·퇴출 이동이 있습니다.',
    VEHICLE_NOT_REMOTE: '원격 모드가 아닌 차량은 이동 명령을 받지 않습니다.',
    VEHICLE_NOT_IDLE: '대기 상태가 아닌 차량은 이동 명령을 받지 않습니다.',
    TARGET_NODE_INVALID: '목표 노드가 지도에 없거나 사용 금지입니다.',
    ALREADY_AT_TARGET: '차량이 이미 그 노드에 있습니다.',
    SOURCE_PORT_NOT_FOUND: '출발 포트가 없습니다.',
    DESTINATION_PORT_NOT_FOUND: '도착 포트가 없습니다.',
    SAME_SOURCE_AND_DESTINATION: '출발과 도착 포트가 같습니다.',
    ACCESS_VEHICLE_TYPE_MISMATCH: '출발·도착 포트의 접근 차종이 다릅니다.',
    ORDER_ALREADY_TERMINAL: '이미 끝났거나 취소 확인을 기다리는 작업입니다.',
    ARRIVED: 'Zone 밖 목적지에 도착해 이동을 마쳤습니다.',
    HAS_ORDER: '작업을 가진 차량은 그 작업으로 Zone을 나갑니다.',
    NO_PLACE_OUTSIDE: 'Zone 밖에 비어 있는 갈 곳이 없습니다.',
    NO_PATH: '목적지까지 경로가 없어 이동을 멈췄습니다.',
    VEHICLE_DISABLED: '사용 중지된 차량은 자동으로 옮기지 않습니다.',
    ALREADY_MOVING: '이미 이동 중입니다.',
    CHARGING: '충전 중입니다.',
    VEHICLE_RELOCATING: '차량이 Zone 밖으로 이동 중이라 새 작업을 줄 수 없습니다.',
    NOT_IN_ZONE: '차량이 이미 Zone 밖에 있습니다.',
    NO_AVAILABLE_VEHICLE: '조건에 맞는 차량이 없습니다.',
    APPROACH_ROUTE_START_UNKNOWN: '차량 위치를 몰라 인수 지점까지 경로를 낼 수 없습니다.',
    APPROACH_ROUTE_NO_PATH: '모든 차량이 인수 지점에 도달할 수 없습니다.',
    DELIVERY_ROUTE_NO_PATH: '인수 지점에서 목적지까지 경로가 없습니다.',
  };
  if (map[decision]) return map[decision];
  // Reasons that carry the vehicle's reported state after a fixed prefix.
  const status: Record<string, string> = {
    ERROR: '에러', PAUSED: '일시정지', CHARGING: '충전', LOADING: '적재', UNLOADING: '하역', RESET: '리셋',
    MANUAL: '수동', SEMI_AUTO: '반자동',
  };
  const said = (s: string) => status[s] ?? s;
  if (decision.startsWith('VEHICLE_STATUS_'))
    return `차량이 ${said(decision.slice(15))} 상태를 보고해 연장하지 않았습니다. 이동 중(MOVING)일 때만 연장합니다.`;
  if (decision.startsWith('HELD_'))
    return `차량이 ${said(decision.slice(5))} 상태라 시뮬레이터가 움직이지 않았습니다. 원격·대기로 되돌리면 이어서 진행합니다.`;
  if (decision.endsWith('_STOPPING'))
    return `${explainDecision(decision.slice(0, -9))} 이미 나간 이동 명령이 있어 차량이 설 때까지 경로를 유지합니다.`;
  // Why a vehicle asking to charge was not sent: the reason is the vehicle's own state, so it is
  // explained by the same words used everywhere else rather than a second set of messages.
  if (decision.startsWith('CHARGE_'))
    return `충전하러 보내지 못했습니다 — ${explainDecision(decision.slice(7))}`;
  return decision;
}
