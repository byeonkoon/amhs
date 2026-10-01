import React from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';
import './monitor.css';
import {
  api, cfgApi, explainDecision, freshness, get, getAs, hasWriteToken, opsApi, send, ApiError, MODE_LABEL, STATUS_LABEL,
  type AssignmentView, type Cfg, type Deadlock, type DeviceCommand, type MapData, type NodeHold,
  type OperationOptions, type Session, type TransportOrder, type Vehicle, type VehicleSnapshot,
  type ZoneOccupancy,
} from './api';
import { AppShell, type MenuKey, type ServerState } from './shell/AppShell';
import { Chip, Empty, Failed, Loading } from './shell/States';
import { Grid, Panel, Row } from './shell/Panel';
import { MapLegend, MapView } from './features/map/MapView';
import { ConfigurationScreen } from './features/configuration/ConfigurationScreen';
import { TransportScreen } from './features/transport/TransportScreen';
import { TrafficScreen } from './features/traffic/TrafficScreen';
import { DiagnosticsScreen } from './features/operations/DiagnosticsScreen';
import { SignInScreen, SystemOptionsScreen } from './features/system/SystemOptionsScreen';
import { EquipmentScreen } from './features/equipment/EquipmentScreen';
import { HistoryScreen } from './features/history/HistoryScreen';

const REVISION_ID = 1;
const ANONYMOUS: Session = { authenticated: false, code: 'anonymous', role: 'VIEWER', allows: [] };

type Loaded = {
  map: MapData; vehicles: Vehicle[]; orders: TransportOrder[]; state: VehicleSnapshot[];
  holds: NodeHold[]; deadlocks: Deadlock[]; inDoubt: DeviceCommand[];
  assignments: AssignmentView[]; options: OperationOptions | null; zones: ZoneOccupancy[];
};

/** One failing read must not blank the whole screen, so optional data falls back to empty. */
const softly = <T,>(promise: Promise<T>, fallback: T) => promise.catch(() => fallback);

function App() {
  const [menu, setMenu] = React.useState<MenuKey | 'system' | 'signin'>('fleet');
  const [data, setData] = React.useState<Loaded | null>(null);
  const [error, setError] = React.useState<unknown>(null);
  const [session, setSession] = React.useState<Session>(ANONYMOUS);
  const [selected, setSelected] = React.useState<number | null>(null);
  const [layer, setLayer] = React.useState<'all' | 'OHT' | 'AGV'>('all');
  const [reload, setReload] = React.useState(0);
  const [loadedAt, setLoadedAt] = React.useState<Date | null>(null);
  // Live screens follow the site by themselves; the reading keeps what is on screen until the
  // next answer arrives instead of blanking it.
  const [autoRefresh, setAutoRefresh] = React.useState(true);
  React.useEffect(() => {
    if (!autoRefresh) return;
    const timer = setInterval(() => setReload(v => v + 1), 2000);
    return () => clearInterval(timer);
  }, [autoRefresh]);

  React.useEffect(() => {
    const controller = new AbortController();
    const signal = controller.signal;
    setError(null);
    softly(getAs<Session>(opsApi.session(), signal), ANONYMOUS).then(setSession);
    Promise.all([
      get<MapData>(api.map(REVISION_ID), signal),
      get<Vehicle[]>(api.vehicles(REVISION_ID), signal),
      softly(get<TransportOrder[]>(api.activeOrders(), signal), [] as TransportOrder[]),
      softly(get<VehicleSnapshot[]>(opsApi.vehicleState(REVISION_ID), signal), [] as VehicleSnapshot[]),
      softly(get<NodeHold[]>(opsApi.reservations(), signal), [] as NodeHold[]),
      softly(get<Deadlock[]>(opsApi.deadlocks(), signal), [] as Deadlock[]),
      softly(get<DeviceCommand[]>(opsApi.inDoubt(), signal), [] as DeviceCommand[]),
      softly(get<AssignmentView[]>(opsApi.assignments(), signal), [] as AssignmentView[]),
      softly(get<Cfg<OperationOptions>>(cfgApi.system(), signal).then(r => r.settings), null),
      softly(get<ZoneOccupancy[]>(opsApi.zones(), signal), [] as ZoneOccupancy[]),
    ])
      .then(([map, vehicles, orders, state, holds, deadlocks, inDoubt, assignments, options, zones]) => {
        setData({ map, vehicles, orders, state, holds, deadlocks, inDoubt, assignments, options, zones });
        setLoadedAt(new Date());
      })
      .catch(cause => { if ((cause as Error).name !== 'AbortError') setError(cause); });
    return () => controller.abort();
  }, [reload]);

  const server: ServerState = error ? 'unreachable' : data ? 'connected' : 'connecting';
  const retry = () => setReload(v => v + 1);
  const page = PAGES[menu];

  return (
    <AppShell active={menu === 'system' || menu === 'signin' ? 'overview' : menu}
      onSelect={k => { setMenu(k); setSelected(null); }}
      onAccount={item => setMenu(item === 'accounts' ? 'system' : item)}
      session={session} runMode={data?.options?.runMode ?? null}
      crumb={['Home', page.crumb, page.title]} title={page.title} subtitle={page.subtitle} server={server}>
      {menu === 'signin' ? <SignInScreen onChanged={retry} />
        : error ? <div className="card"><Failed error={error} onRetry={retry} /></div>
          : !data ? <div className="card"><Loading what={page.title} /></div>
            : menu === 'system' ? <SystemOptionsScreen session={session} />
              : menu === 'resources' ? <ConfigurationScreen map={data.map} session={session} />
              : menu === 'equipment' ? <EquipmentScreen session={session} revisionId={REVISION_ID} />
              : menu === 'history' ? <HistoryScreen map={data.map} />
                : menu === 'transport'
                  ? <TransportScreen orders={data.orders} map={data.map} vehicles={data.state}
                      loadedAt={loadedAt} onRetry={retry} />
                  : menu === 'traffic'
                    ? <TrafficScreen map={data.map} holds={data.holds} deadlocks={data.deadlocks} vehicles={data.state}
                        zones={data.zones} />
                    : menu === 'diagnostics'
                      ? <DiagnosticsScreen map={data.map} revisionId={REVISION_ID} vehicles={data.state}
                          inDoubt={data.inDoubt} onChanged={retry} />
                      : <MonitorScreen data={data} selected={selected} onSelect={setSelected}
                          layer={layer} onLayer={setLayer} loadedAt={loadedAt} onRetry={retry}
                          autoRefresh={autoRefresh} onAutoRefresh={setAutoRefresh} />}
    </AppShell>
  );
}

const PAGES: Record<MenuKey | 'system' | 'signin', { title: string; subtitle: string; crumb: string }> = {
  overview: { title: '개요', subtitle: 'Overview · Status / KPI', crumb: 'Overview' },
  fleet: { title: '운전 현황', subtitle: 'Fleet · Map / Vehicles / Jobs', crumb: 'Fleet' },
  resources: { title: '자원 구성', subtitle: 'Resources · Vehicle / Node / Segment / Port', crumb: 'Resources' },
  equipment: { title: '설비', subtitle: 'Equipment · EQP / Rack / Slot', crumb: 'Equipment' },
  transport: { title: '반송 작업', subtitle: 'Transport · Job / Route', crumb: 'Transport' },
  traffic: { title: '구간 점유·교착', subtitle: 'Traffic · Reservation / Deadlock', crumb: 'Traffic' },
  inventory: { title: '재고', subtitle: 'Inventory · Carrier / Stocker', crumb: 'Inventory' },
  alarms: { title: '알람', subtitle: 'Alarms · Event / Notification', crumb: 'Alarms' },
  history: { title: '이력', subtitle: 'History · Log / Trace', crumb: 'History' },
  diagnostics: { title: '장비 명령·상태 보고', subtitle: 'Diagnostics · Health / Recovery', crumb: 'Diagnostics' },
  system: { title: '시스템 옵션', subtitle: 'System · Site-wide options', crumb: 'Account' },
  signin: { title: '접속 토큰', subtitle: 'Session · Access token', crumb: 'Account' },
};

/**
 * The screen an operator watches. The map takes the room; the numbers around it are one thin
 * strip, and the detail sits in panels that fold, so more can be on screen without the map
 * shrinking to make space for it.
 */
function MonitorScreen(props: {
  data: Loaded; selected: number | null; onSelect: (id: number | null) => void;
  layer: 'all' | 'OHT' | 'AGV'; onLayer: (l: 'all' | 'OHT' | 'AGV') => void;
  loadedAt: Date | null; onRetry: () => void;
  autoRefresh: boolean; onAutoRefresh: (on: boolean) => void;
}) {
  const { map, vehicles, state, orders, holds, assignments, deadlocks, options } = props.data;
  const [target, setTarget] = React.useState<number | null>(null);
  const nodeCode = (id: number | null) => id === null ? '위치 불명' : map.nodes.find(n => n.id === id)?.code ?? String(id);
  const portCode = (id: number) => map.ports.find(p => p.id === id)?.code ?? String(id);
  const staleAfter = (options?.positionStaleSeconds ?? 10) * 1000;

  const holdsOf = (vehicleId: number) => holds.filter(h => h.vehicleId === vehicleId).length;
  const vehicleOf = (orderId: number) => assignments.find(a => a.orderId === orderId)?.vehicleId ?? null;
  const codeOf = (vehicleId: number | null) =>
    vehicleId === null ? '—' : state.find(v => v.id === vehicleId)?.code ?? `#${vehicleId}`;

  const active = orders.filter(o => !TERMINAL.has(o.status));
  const moving = state.filter(v => v.online && !v.idle).length;
  const selectedVehicle = props.selected === null ? null : vehicles.find(v => v.id === props.selected) ?? null;

  return (
    <>
      <div className="statbar">
        <span className="s"><span className="k">REV</span><span className="v">{map.revisionId}</span>
          <span className="d">노드 {map.nodes.length} · 구간 {map.edges.length} · 포트 {map.ports.length}</span></span>
        <span className="s"><span className="k">OHT</span><span className="v">{state.filter(v => v.vType === 'OHT').length}</span></span>
        <span className="s"><span className="k">AGV</span><span className="v">{state.filter(v => v.vType === 'AGV').length}</span></span>
        <span className="s"><span className="k">이동 중</span><span className="v">{moving}</span></span>
        <span className="s"><span className="k">진행 작업</span><span className="v">{active.length}</span></span>
        <span className="s"><span className="k">점유 노드</span><span className="v">{holds.length}</span></span>
        <span className="s"><span className="k">교착</span>
          <span className="v" style={deadlocks.length ? { color: 'var(--crit)' } : undefined}>{deadlocks.length}</span></span>
        <span className="s"><span className="k">조회</span>
          <span className="d mono">{props.loadedAt ? props.loadedAt.toLocaleTimeString('ko-KR', { hour12: false }) : '—'}</span>
          <button type="button" onClick={props.onRetry} style={tiny}>새로고침</button>
          <label style={{ fontSize: 11, marginLeft: 6, display: 'inline-flex', gap: 4, alignItems: 'center' }}>
            <input type="checkbox" checked={props.autoRefresh} onChange={e => props.onAutoRefresh(e.target.checked)} />자동 2초
          </label></span>
      </div>

      <div className="grid g-mon">
        <section className="card">
          <div className="ch">
            <h2>설비 레이아웃</h2>
            <div style={{ display: 'flex', gap: 5, marginLeft: 'auto', alignItems: 'center' }}>
              {(['all', 'OHT', 'AGV'] as const).map(l => (
                <button key={l} type="button" onClick={() => props.onLayer(l)} aria-pressed={props.layer === l}
                  style={{
                    border: '1px solid var(--ln2)', borderRadius: 5, padding: '3px 10px', fontSize: 11, cursor: 'pointer',
                    background: props.layer === l ? 'var(--acc)' : 'var(--srf)',
                    color: props.layer === l ? '#fff' : 'var(--ink2)',
                  }}>{l === 'all' ? '전체' : l}</button>
              ))}
            </div>
          </div>
          {map.nodes.length === 0
            ? <Empty title="등록된 지도 노드가 없습니다" hint="이 지도 revision에 노드가 없습니다." />
            : <MapView map={map} vehicles={state} selectedId={props.selected}
                onSelect={v => props.onSelect(v.id === props.selected ? null : v.id)} layer={props.layer}
                targetNodeId={target} onNodeSelect={setTarget} />}
          <MapLegend />
        </section>

        <div style={{ display: 'grid', gap: 9, alignContent: 'start' }}>
          <Panel id="mon-vehicles" title="차량 상태" aux={`${state.length}대`} defaultOpen>
            {state.length === 0 ? <Empty title="등록된 차량이 없습니다" /> : (
              <Grid cols="58px 1fr 48px 58px 44px 36px" head={['차량', '위치', '모드', '상태', '적재', '점유']}>
                {state.map(v => (
                  <Row key={v.id} selected={props.selected === v.id}
                    onClick={() => props.onSelect(v.id === props.selected ? null : v.id)}>
                    <span className="mono">{v.code}</span>
                    <span className={v.nodeId === null ? 'mono muted' : 'mono'}>{nodeCode(v.nodeId)}</span>
                    <span><Chip tone={modeTone(v)}>{modeLabel(v)}</Chip></span>
                    <span><Chip tone={stateTone(v)}>{stateLabel(v)}</Chip></span>
                    <span><Chip tone={v.carrierPresent ? 'ac' : 'id'}>{v.carrierPresent ? '있음' : '없음'}</Chip></span>
                    <span className="mono">{holdsOf(v.id) || '—'}</span>
                  </Row>
                ))}
              </Grid>
            )}
          </Panel>

          <Panel id="mon-move" title="수동 이동" aux="차량과 목표 노드를 골라 보냅니다" defaultOpen padded>
            <ManualMove map={map} vehicles={state} selected={props.selected} onSelect={props.onSelect}
              target={target} onTarget={setTarget} onDone={props.onRetry} />
          </Panel>

          <Panel id="mon-jobs" title="작업 진행" aux={`진행 ${active.length}건`} defaultOpen>
            {active.length === 0 ? <Empty title="진행 중인 작업이 없습니다" hint="끝난 작업은 History에서 봅니다." /> : (
              <Grid cols="34px 68px 1fr 60px" head={['번호', '단계', '설명', '할당']}>
                {active.map(o => {
                  const step = STEP[o.status] ?? { label: o.status, note: '' };
                  return (
                    <Row key={o.id}>
                      <span className="mono">{o.id}</span>
                      <span><Chip tone={toneOf(o.status)}>{step.label}</Chip></span>
                      <span title={`${step.note} · ${portCode(o.sourcePortId)} → ${portCode(o.destinationPortId)}`}>
                        {step.note}
                      </span>
                      <span className="mono">{codeOf(vehicleOf(o.id))}</span>
                    </Row>
                  );
                })}
              </Grid>
            )}
          </Panel>

          <Panel id="mon-detail" title="선택 상세" aux={selectedVehicle?.code ?? '없음'} padded>
            {!selectedVehicle
              ? <p className="hint" style={{ margin: 0 }}>지도나 목록에서 차량을 고르면 상세가 표시됩니다.</p>
              : <VehicleDetail vehicle={selectedVehicle} reported={state.find(v => v.id === selectedVehicle.id) ?? null}
                  nodeCode={nodeCode} staleAfter={staleAfter} simulated={options?.runMode === 'SIMULATION'} onChanged={props.onRetry}
                  run={holds.filter(h => h.vehicleId === selectedVehicle.id).sort((a, b) => a.sequence - b.sequence)} />}
          </Panel>

          <Panel id="mon-ports" title="포트" aux={`${map.ports.length}개`}>
            <Grid cols="66px 62px 1fr" head={['포트', '차종', '연결 노드']}>
              {map.ports.map(p => (
                <Row key={p.id}>
                  <span className="mono">{p.code}</span>
                  <span><Chip tone={p.accessVehicleType === 'OHT' ? 'ac' : 'id'}>{p.accessVehicleType}</Chip></span>
                  <span className="mono">{nodeCode(p.linkedNodeId)}</span>
                </Row>
              ))}
            </Grid>
          </Panel>
        </div>
      </div>
    </>
  );
}

function VehicleDetail(props: {
  vehicle: Vehicle; reported: VehicleSnapshot | null; nodeCode: (id: number | null) => string;
  staleAfter: number; run: NodeHold[]; simulated?: boolean; onChanged?: () => void;
}) {
  const r = props.reported;
  const [stateError, setStateError] = React.useState<string | null>(null);
  // In simulation a person can put a vehicle into manual, pause it, fault it, or give it back to
  // the controller, the way a vehicle on site would report it. The simulated vehicle then holds.
  async function setState(mode: string, status: string) {
    if (!r) return;
    setStateError(null);
    try {
      await send(opsApi.vehicleState1(REVISION_ID, r.id), 'PUT', {
        nodeId: r.nodeId, online: true, carrierPresent: r.carrierPresent, source: 'SIMULATED', mode, status,
      }, '시뮬레이션 상태 변경');
      props.onChanged?.();
    } catch (cause) {
      setStateError(cause instanceof ApiError ? explainDecision(cause.message) : '바꾸지 못했습니다.');
    }
  }
  return (
    <div style={{ display: 'grid', gap: 7, fontSize: 12 }}>
      <div style={{ display: 'flex', gap: 7, alignItems: 'center', flexWrap: 'wrap' }}>
        <b className="mono">{props.vehicle.code}</b>
        <Chip tone={props.vehicle.vType === 'OHT' ? 'ac' : 'id'}>{props.vehicle.vType}</Chip>
        {r && <Chip tone={sourceTone(r.source)}>{sourceLabel(r.source)}</Chip>}
        {r && <Chip tone={freshness(r, props.staleAfter).tone}>{freshness(r, props.staleAfter).label}</Chip>}
      </div>
      <div>위치 <span className="mono">{props.nodeCode(r?.nodeId ?? null)}</span></div>
      {r && (
        <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
          <Chip tone={r.online ? 'ok' : 'id'}>{r.online ? '온라인' : '오프라인'}</Chip>
          <Chip tone={modeTone(r)}>모드 {modeLabel(r)}</Chip>
          <Chip tone={stateTone(r)}>상태 {stateLabel(r)}</Chip>
          <Chip tone={r.carrierPresent ? 'ac' : 'id'}>{r.carrierPresent ? '화물 있음' : '화물 없음'}</Chip>
        </div>
      )}
      <div>
        점유 구간{' '}
        {props.run.length === 0
          ? <span className="muted">없음</span>
          : <span className="mono">{props.run.map(h => props.nodeCode(h.nodeId)).join(' › ')}</span>}
      </div>
      {props.simulated && r && (
        <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap', alignItems: 'center' }}>
          <span className="hint">시뮬레이션 상태</span>
          {([['REMOTE', 'IDLE', '원격·대기'], ['MANUAL', 'IDLE', '수동'], ['REMOTE', 'PAUSED', '일시정지'],
            ['REMOTE', 'ERROR', '에러'], ['REMOTE', 'CHARGING', '충전']] as const).map(([m, s, label]) => (
            <button key={label} type="button" style={tiny} disabled={!hasWriteToken()} onClick={() => setState(m, s)}>{label}</button>
          ))}
          {stateError && <span className="hint" style={{ color: 'var(--crit)' }}>{stateError}</span>}
        </div>
      )}
      {r?.source === 'INITIAL_CONFIGURATION' && (
        <p className="hint" style={{ margin: 0 }}>
          아직 아무도 이 차량의 위치를 보고하지 않았습니다. 표시된 값은 설정이며 장비 근거가 아닙니다.
        </p>
      )}
    </div>
  );
}

const TERMINAL = new Set(['COMPLETED', 'CANCELLED', 'FAILED']);
/** Each state as the step it is, with what is actually happening in it. */
const STEP: Record<string, { label: string; note: string }> = {
  QUEUED: { label: '대기', note: '차량 배정을 기다리는 중' },
  ASSIGNED: { label: '배정', note: '차량을 잡았고 출발 구간 확보를 기다리는 중' },
  TO_SOURCE: { label: '인수 이동', note: '인수 지점으로 이동 중' },
  LOADING: { label: '인수', note: '화물을 싣는 중' },
  TO_DESTINATION: { label: '반송', note: '목적지로 이동 중' },
  UNLOADING: { label: '인계', note: '화물을 내리는 중' },
  COMPLETED: { label: '완료', note: '반송이 끝났습니다' },
  CANCEL_REQUESTED: { label: '취소 요청', note: '차량이 명령을 받았거나 화물을 실은 뒤라 장비 확인을 기다리는 중' },
  CANCELLED: { label: '취소', note: '취소되었습니다' },
  FAILED: { label: '실패', note: '수행하지 못했습니다' },
  RECOVERY_REQUIRED: { label: '복구 필요', note: '화물 위치를 확인해야 합니다' },
};

/** Status as the vehicle reports it, on its own axis apart from the mode. */
function stateLabel(v: VehicleSnapshot): string {
  if (!v.online) return '오프라인';
  const status = v.status ?? (v.idle ? 'IDLE' : 'MOVING');
  return STATUS_LABEL[status] ?? status;
}
function stateTone(v: VehicleSnapshot): string {
  if (!v.online) return 'id';
  const status = v.status ?? (v.idle ? 'IDLE' : 'MOVING');
  if (status === 'ERROR') return 'er';
  if (status === 'PAUSED' || status === 'RESET') return 'wr';
  if (status === 'IDLE') return 'ok';
  return 'ac';
}
function modeLabel(v: VehicleSnapshot): string {
  const mode = v.mode ?? (v.remote ? 'REMOTE' : 'MANUAL');
  return MODE_LABEL[mode] ?? mode;
}
function modeTone(v: VehicleSnapshot): string {
  const mode = v.mode ?? (v.remote ? 'REMOTE' : 'MANUAL');
  return mode === 'REMOTE' ? 'ok' : mode === 'SEMI_AUTO' ? 'wr' : 'er';
}

/**
 * Sending one vehicle to one node by hand. The server refuses unless the vehicle is in use, under
 * remote control, idle and free of any order or other move; the screen says the same thing up
 * front so the button is not a guess. A node can be picked from the list or by clicking the map.
 */
function ManualMove(props: {
  map: MapData; vehicles: VehicleSnapshot[]; selected: number | null; onSelect: (id: number | null) => void;
  target: number | null; onTarget: (id: number | null) => void; onDone: () => void;
}) {
  const [busy, setBusy] = React.useState(false);
  const [notice, setNotice] = React.useState<{ tone: string; text: string } | null>(null);
  const vehicle = props.vehicles.find(v => v.id === props.selected) ?? null;
  const networkOf = (nodeId: number) => {
    const port = props.map.ports.find(p => p.linkedNodeId === nodeId);
    if (port) return port.accessVehicleType;
    return props.map.nodes.find(n => n.id === nodeId)?.code.startsWith('AGV') ? 'AGV' : 'OHT';
  };
  const nodes = props.map.nodes.filter(n => n.enabled && (!vehicle || networkOf(n.id) === vehicle.vType));
  const blocker = !vehicle ? '차량을 고르세요.'
    : !vehicle.online ? '오프라인 차량입니다.'
      : (vehicle.mode ?? (vehicle.remote ? 'REMOTE' : 'MANUAL')) !== 'REMOTE' ? '원격 모드가 아닙니다.'
        : !(vehicle.status ? vehicle.status === 'IDLE' : vehicle.idle) ? '대기 상태가 아닙니다.'
          : props.target === null ? '목표 노드를 고르세요(목록 또는 지도 클릭).'
            : props.target === vehicle.nodeId ? '이미 그 노드에 있습니다.'
              : !hasWriteToken() ? '토큰을 입력해야 명령할 수 있습니다.' : null;

  async function move() {
    if (!vehicle || props.target === null) return;
    setBusy(true); setNotice(null);
    try {
      const res = await send<{ decision: string; addedNodeIds: number[] }>(opsApi.moveVehicle(vehicle.id), 'POST',
        { targetNodeId: props.target }, '수동 이동');
      setNotice({ tone: res.decision === 'MOVING_TO_TARGET' ? 'ok' : 'wr', text: explainDecision(res.decision) });
      props.onDone();
    } catch (cause) {
      setNotice({ tone: 'er', text: cause instanceof ApiError ? explainDecision(cause.message) : '명령하지 못했습니다.' });
    } finally { setBusy(false); }
  }

  return (
    <div style={{ display: 'grid', gap: 7, fontSize: 12 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '52px 1fr', gap: 6, alignItems: 'center' }}>
        <span>차량</span>
        <select value={props.selected ?? ''} onChange={e => props.onSelect(e.target.value ? Number(e.target.value) : null)} style={selectStyle}>
          <option value="">선택</option>
          {props.vehicles.map(v => <option key={v.id} value={v.id}>{v.code} · {v.vType}</option>)}
        </select>
        <span>목표</span>
        <select value={props.target ?? ''} onChange={e => props.onTarget(e.target.value ? Number(e.target.value) : null)} style={selectStyle}>
          <option value="">선택</option>
          {nodes.map(n => <option key={n.id} value={n.id}>{n.code}</option>)}
        </select>
      </div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <button type="button" disabled={busy || blocker !== null} onClick={move} style={{
          border: '1px solid var(--acc)', background: blocker ? 'var(--srf)' : 'var(--acc)', color: blocker ? 'var(--mut)' : '#fff',
          borderRadius: 5, padding: '4px 14px', fontSize: 12, cursor: blocker ? 'default' : 'pointer' }}>
          {busy ? '보내는 중…' : '이동'}
        </button>
        {blocker && <span className="hint">{blocker}</span>}
      </div>
      {notice && <p style={{ margin: 0 }}><Chip tone={notice.tone}>{notice.tone === 'er' ? '거부' : '완료'}</Chip> {notice.text}</p>}
    </div>
  );
}
const selectStyle: React.CSSProperties = {
  border: '1px solid var(--ln2)', borderRadius: 5, padding: '3px 6px', fontSize: 12, background: 'var(--srf)', color: 'var(--ink)',
};
function sourceLabel(s: string) { return s === 'DEVICE' ? '장비 보고' : s === 'SIMULATED' ? '모의 보고' : '설정값'; }
function sourceTone(s: string) { return s === 'DEVICE' ? 'ok' : s === 'SIMULATED' ? 'wr' : 'id'; }
function toneOf(status: string): string {
  if (status === 'COMPLETED') return 'ok';
  if (status === 'FAILED' || status === 'RECOVERY_REQUIRED') return 'er';
  if (status === 'CANCELLED' || status === 'CANCEL_REQUESTED') return 'wr';
  if (status === 'QUEUED') return 'id';
  return 'ac';
}

const tiny: React.CSSProperties = {
  border: '1px solid var(--ln2)', background: 'var(--srf)', color: 'var(--ink2)',
  borderRadius: 4, padding: '1px 7px', fontSize: 10, cursor: 'pointer', marginLeft: 6,
};

createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>);
