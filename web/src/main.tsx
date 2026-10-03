import React from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';
import './monitor.css';
import {
  alarmApi, api, cfgApi, explainDecision, freshness, get, getAs, hasWriteToken, opsApi, send, ApiError, MODE_LABEL, STATUS_LABEL,
  type AlarmCount,
  type AssignmentView, type Cfg, type Deadlock, type DeadlockPlan, type DeadlockRecovery, type DeviceCommand, type MapData, type NodeHold,
  type OperationOptions, type Session, type TransportOrder, type Vehicle, type VehicleSnapshot,
  type VehicleHeading, type VehicleMove, type ZoneOccupancy,
} from './api';
import { AppShell, mayOpen, type MenuKey, type ServerState } from './shell/AppShell';
import { Chip, Empty, Failed, Loading } from './shell/States';
import { Grid, Panel, Row } from './shell/Panel';
import { MapLegend, MapView, layerOfNode } from './features/map/MapView';
// three.js is loaded only when somebody switches to 3D.
const MapView3D = React.lazy(() => import('./features/map/MapView3D'));
import { ConfigurationScreen } from './features/configuration/ConfigurationScreen';
import { TransportScreen } from './features/transport/TransportScreen';
import { TrafficScreen } from './features/traffic/TrafficScreen';
import { DiagnosticsScreen } from './features/operations/DiagnosticsScreen';
import { SystemOptionsScreen } from './features/system/SystemOptionsScreen';
import { SignInScreen } from './features/system/SignInScreen';
import { AccountsScreen } from './features/system/AccountsScreen';
import { EquipmentScreen } from './features/equipment/EquipmentScreen';
import { InventoryScreen } from './features/inventory/InventoryScreen';
import { AlarmsScreen } from './features/alarms/AlarmsScreen';
import { OverviewScreen } from './features/overview/OverviewScreen';
import { HistoryScreen } from './features/history/HistoryScreen';
import { useLive, type Live, type LiveParts } from './live';

const REVISION_ID = 1;
/** Pages reached from the account menu rather than the sidebar, so no sidebar entry is current. */
type AccountPage = 'system' | 'accounts' | 'signin';
const ACCOUNT_PAGES = new Set<string>(['system', 'accounts', 'signin']);
/** Nobody in particular: the lowest level, which is what a screen gets before anyone logs in. */
const ANONYMOUS: Session = { authenticated: false, code: 'anonymous', role: 'VIEWER', level: 1, allows: ['monitor'] };

type Loaded = {
  map: MapData; vehicles: Vehicle[]; orders: TransportOrder[]; state: VehicleSnapshot[];
  holds: NodeHold[]; deadlocks: Deadlock[]; inDoubt: DeviceCommand[];
  assignments: AssignmentView[]; options: OperationOptions | null; zones: ZoneOccupancy[];
  moves: VehicleMove[];
  headings: VehicleHeading[];
  plans: DeadlockPlan[]; recoveries: DeadlockRecovery[];
};

/** The live parts laid over what was read, under the names the screens use. */
function withLive(d: Loaded, p: LiveParts): Loaded {
  return {
    ...d,
    state: p.vehicles ?? d.state, holds: p.holds ?? d.holds, headings: p.headings ?? d.headings,
    moves: p.moves ?? d.moves, assignments: p.assignments ?? d.assignments, orders: p.orders ?? d.orders,
    deadlocks: p.deadlocks ?? d.deadlocks, zones: p.zones ?? d.zones, inDoubt: p.inDoubt ?? d.inDoubt,
    plans: p.deadlockPlans ?? d.plans, recoveries: p.recoveries ?? d.recoveries,
  };
}

/** One failing read must not blank the whole screen, so optional data falls back to empty. */
const softly = <T,>(promise: Promise<T>, fallback: T) => promise.catch(() => fallback);

function App() {
  const [menu, setMenu] = React.useState<MenuKey | AccountPage>('fleet');
  const [data, setData] = React.useState<Loaded | null>(null);
  const [error, setError] = React.useState<unknown>(null);
  const [session, setSession] = React.useState<Session>(ANONYMOUS);
  const [selected, setSelected] = React.useState<number | null>(null);
  const [layer, setLayer] = React.useState<'all' | 'OHT' | 'AGV'>('all');
  const [reload, setReload] = React.useState(0);
  const [loadedAt, setLoadedAt] = React.useState<Date | null>(null);
  const [alarmCount, setAlarmCount] = React.useState<AlarmCount | null>(null);
  // Live screens follow the site by themselves; the reading keeps what is on screen until the
  // next answer arrives instead of blanking it.
  const [autoRefresh, setAutoRefresh] = React.useState(true);
  // While the live feed is in step, what moves comes from it; the reading below then only keeps the
  // rest (map, configuration, options) current, and never puts an older answer over a live one.
  const live = useLive(autoRefresh);
  const inStep = live.status === 'live';
  const liveRef = React.useRef<Live>(live);
  liveRef.current = live;
  React.useEffect(() => {
    if (inStep) setData(d => d ? withLive(d, live.parts) : d);
  }, [live, inStep]);
  React.useEffect(() => {
    if (!autoRefresh) return;
    const timer = setInterval(() => setReload(v => v + 1), inStep ? 10000 : 2000);
    return () => clearInterval(timer);
  }, [autoRefresh, inStep]);
  // Without the live feed, on the layout screen vehicle positions are read more often than the rest
  // so a vehicle driving between nodes is seen moving rather than jumping every two seconds.
  React.useEffect(() => {
    if (!autoRefresh || menu !== 'fleet' || inStep) return;
    let busy = false;
    const controller = new AbortController();
    const timer = setInterval(() => {
      if (busy) return;
      busy = true;
      get<VehicleSnapshot[]>(opsApi.vehicleState(REVISION_ID), controller.signal)
        .then(state => setData(d => d ? { ...d, state } : d))
        .catch(() => { /* the two-second reading reports a lost connection */ })
        .finally(() => { busy = false; });
    }, 300);
    return () => { clearInterval(timer); controller.abort(); };
  }, [autoRefresh, menu, inStep]);

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
      softly(get<VehicleMove[]>(opsApi.vehicleMoves(), signal), [] as VehicleMove[]),
      softly(get<VehicleHeading[]>(opsApi.vehicleHeadings(), signal), [] as VehicleHeading[]),
      softly(get<DeadlockPlan[]>(opsApi.deadlockPlans(), signal), [] as DeadlockPlan[]),
      softly(get<DeadlockRecovery[]>(opsApi.deadlockRecoveries(), signal), [] as DeadlockRecovery[]),
    ])
      .then(([map, vehicles, orders, state, holds, deadlocks, inDoubt, assignments, options, zones, moves, headings, plans, recoveries]) => {
        const read: Loaded = {
          map, vehicles, orders, state, holds, deadlocks, inDoubt, assignments, options, zones, moves, headings, plans, recoveries,
        };
        const now = liveRef.current;
        setData(now.status === 'live' ? withLive(read, now.parts) : read);
        setLoadedAt(new Date());
      })
      .catch(cause => { if ((cause as Error).name !== 'AbortError') setError(cause); });
    return () => controller.abort();
  }, [reload]);

  // The count rides on the shell rather than on the alarms screen, so a fault that appears while
  // somebody is looking at the map is visible from the map.
  React.useEffect(() => {
    let live = true;
    const read = () => {
      const c = new AbortController();
      softly(get<AlarmCount>(alarmApi.count(), c.signal), null).then(v => { if (live && v) setAlarmCount(v); });
      return c;
    };
    let controller = read();
    const timer = window.setInterval(() => { controller.abort(); controller = read(); }, 5000);
    return () => { live = false; window.clearInterval(timer); controller.abort(); };
  }, []);

  // Logging out, or being put on a lower level while a screen is open, takes the screen away too.
  // A permission that is withdrawn but still usable on the screen already open was not withdrawn.
  React.useEffect(() => {
    if (ACCOUNT_PAGES.has(menu)) {
      if (menu !== 'signin' && !session.allows.includes(menu)) setMenu('fleet');
      return;
    }
    if (!mayOpen(session, menu as MenuKey)) setMenu('fleet');
  }, [session, menu]);

  const server: ServerState = error ? 'unreachable' : data ? 'connected' : 'connecting';
  const retry = () => setReload(v => v + 1);
  const page = PAGES[menu];

  return (
    <AppShell active={ACCOUNT_PAGES.has(menu) ? 'overview' : (menu as MenuKey)}
      onSelect={k => { setMenu(k); setSelected(null); }}
      onAccount={setMenu}
      session={session} runMode={data?.options?.runMode ?? null}
      alarms={alarmCount ?? undefined}
      crumb={['Home', page.crumb, page.title]} title={page.title} subtitle={page.subtitle} server={server}>
      {menu === 'signin' ? <SignInScreen session={session} onChanged={retry} />
        : error ? <div className="card"><Failed error={error} onRetry={retry} /></div>
          : !data ? <div className="card"><Loading what={page.title} /></div>
            : menu === 'system' ? <SystemOptionsScreen session={session} />
              : menu === 'accounts' ? <AccountsScreen session={session} />
              : menu === 'resources' ? <ConfigurationScreen map={data.map} session={session} />
              : menu === 'equipment' ? <EquipmentScreen session={session} revisionId={REVISION_ID} simulated={data.options?.runMode === 'SIMULATION'} />
              : menu === 'inventory' ? <InventoryScreen session={session} map={data.map} orders={data.orders} />
              : menu === 'alarms' ? <AlarmsScreen onGo={setMenu} />
              : menu === 'overview' ? <OverviewScreen onGo={setMenu} />
              : menu === 'history' ? <HistoryScreen map={data.map} />
                : menu === 'transport'
                  ? <TransportScreen orders={data.orders} map={data.map} vehicles={data.state}
                      loadedAt={loadedAt} onRetry={retry} />
                  : menu === 'traffic'
                    ? <TrafficScreen map={data.map} holds={data.holds} deadlocks={data.deadlocks} vehicles={data.state}
                        zones={data.zones} plans={data.plans} recoveries={data.recoveries} onChanged={retry} />
                    : menu === 'diagnostics'
                      ? <DiagnosticsScreen map={data.map} revisionId={REVISION_ID} vehicles={data.state}
                          inDoubt={data.inDoubt} onChanged={retry} />
                      : <MonitorScreen data={data} selected={selected} onSelect={setSelected}
                          layer={layer} onLayer={setLayer} loadedAt={loadedAt} onRetry={retry}
                          autoRefresh={autoRefresh} onAutoRefresh={setAutoRefresh} live={live} />}
    </AppShell>
  );
}

/**
 * Whether what is on screen is following the site. Out of step or disconnected is never shown as
 * normal: the screen says it is reading every two seconds instead, or that it is not following at all.
 */
function LiveBadge(props: { live: Live; enabled: boolean }) {
  const look = !props.enabled ? { tone: 'id', label: '갱신 꺼짐', title: '자동 갱신을 끄면 새로고침할 때만 바뀝니다.' }
    : props.live.status === 'live' ? { tone: 'ok', label: `실시간 #${props.live.seq}`, title: '서버가 바뀐 내용을 바로 보냅니다. 번호가 이어지지 않으면 전체를 다시 받습니다.' }
      // Connected and in step, but the server named parts it could not read. Those parts show the
      // last thing it could read, so they must not be presented as the present — and saying which
      // ones is the difference between a warning somebody can act on and one they learn to ignore.
      : props.live.status === 'stale' ? {
        tone: 'er',
        label: `조회 불가 · ${partNames(props.live.unread)}`,
        title: `연결은 되어 있지만 서버가 ${partNames(props.live.unread)}을(를) 읽지 못했습니다. 그 항목은 마지막으로 읽힌 상태이며 현재가 아닙니다. 나머지 항목은 계속 갱신됩니다.`,
      }
      : props.live.status === 'resyncing' ? { tone: 'wr', label: '재동기화 중', title: '빠진 변경이 있어 전체를 다시 받는 중입니다.' }
        : props.live.status === 'connecting' ? { tone: 'wr', label: '실시간 연결 중', title: '연결되기 전까지 2초마다 조회합니다.' }
          : { tone: 'er', label: '실시간 끊김 · 2초 조회', title: '실시간 연결이 끊겨 2초마다 조회합니다. 다시 연결을 시도합니다.' };
  return <span style={{ marginLeft: 6 }} title={look.title}><Chip tone={look.tone}>{look.label}</Chip></span>;
}

/** The server's part names as the screens name them, so a warning says what an operator looks at. */
const PART_LABEL: Record<string, string> = {
  revisionId: '지도 버전', vehicles: '차량', holds: '구간 점유', headings: '목적지', moves: '이동',
  assignments: '배차', orders: '반송 작업', deadlocks: '교착', zones: 'Zone', inDoubt: '결과 미확인 명령',
  deadlockPlans: '교착 해소안', recoveries: '양보 복구',
};
function partNames(parts: string[]): string {
  if (parts.length === 0) return '일부 항목';
  return parts.map(p => PART_LABEL[p] ?? p).join('·');
}

const PAGES: Record<MenuKey | AccountPage, { title: string; subtitle: string; crumb: string }> = {
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
  accounts: { title: '계정·권한', subtitle: 'Accounts · Who may do what', crumb: 'Account' },
  signin: { title: '로그인', subtitle: 'Session · Sign in', crumb: 'Account' },
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
  live: Live;
}) {
  const { map, vehicles, state, orders, holds, assignments, deadlocks, options } = props.data;
  const [target, setTarget] = React.useState<number | null>(null);
  // 2D or 3D, remembered per browser; the 2D plan stays the default.
  const [mapMode, setMapMode] = React.useState<'2D' | '3D'>(() => {
    try { return localStorage.getItem('amhs.mapMode') === '3D' ? '3D' : '2D'; } catch { return '2D'; }
  });
  const chooseMapMode = (m: '2D' | '3D') => {
    setMapMode(m);
    try { localStorage.setItem('amhs.mapMode', m); } catch { /* the choice just is not remembered */ }
  };
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
            <input type="checkbox" checked={props.autoRefresh} onChange={e => props.onAutoRefresh(e.target.checked)} />자동 갱신
          </label>
          <LiveBadge live={props.live} enabled={props.autoRefresh} /></span>
      </div>

      <div className="grid g-mon">
        <section className="card">
          <div className="ch">
            <h2>설비 레이아웃</h2>
            <div style={{ display: 'flex', gap: 5, marginLeft: 'auto', alignItems: 'center' }}>
              <ViewSwitch value={mapMode} onChange={chooseMapMode} />
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
            : mapMode === '3D'
              ? <React.Suspense fallback={<Loading what="3D 보기" />}>
                  <MapView3D map={map} vehicles={state} selectedId={props.selected} holds={holds} headings={props.data.headings}
                    onSelect={v => props.onSelect(v.id === props.selected ? null : v.id)} layer={props.layer}
                    targetNodeId={target} onNodeSelect={setTarget} />
                </React.Suspense>
              : <MapView map={map} vehicles={state} selectedId={props.selected} holds={holds} headings={props.data.headings}
                  onSelect={v => props.onSelect(v.id === props.selected ? null : v.id)} layer={props.layer}
                  targetNodeId={target} onNodeSelect={setTarget} />}
          <MapLegend mode={mapMode} />
        </section>

        <div style={{ display: 'grid', gap: 9, alignContent: 'start' }}>
          <Panel id="mon-vehicles" title="차량 상태" aux={`${state.length}대`} defaultOpen>
            {state.length === 0 ? <Empty title="등록된 차량이 없습니다" /> : (
              <Grid cols="58px 1fr 48px 58px 44px 44px 36px" head={['차량', '위치', '모드', '상태', '적재', '배터리', '점유']}>
                {state.map(v => (
                  <Row key={v.id} selected={props.selected === v.id}
                    onClick={() => props.onSelect(v.id === props.selected ? null : v.id)}>
                    <span className="mono">{v.code}</span>
                    <span className={v.nodeId === null ? 'mono muted' : 'mono'}>{nodeCode(v.nodeId)}</span>
                    <span><Chip tone={modeTone(v)}>{modeLabel(v)}</Chip></span>
                    <span>{v.outOfServiceSince
                      // Out of service is said instead of the reported status, not beside it: what
                      // the vehicle is doing stops mattering once nobody may work it.
                      ? <Chip tone="wr" title={`${v.outOfServiceReason ?? ''}${v.outOfServiceBy ? ` · ${v.outOfServiceBy}` : ''}`}>
                          서비스 제외</Chip>
                      : <Chip tone={stateTone(v)}>{stateLabel(v)}</Chip>}</span>
                    <span><Chip tone={v.carrierPresent ? 'ac' : 'id'}>{v.carrierPresent ? '있음' : '없음'}</Chip></span>
                    <span className="mono" style={{ color: batteryColor(v, options) }} title={chargeMeaning(v)}>
                      {v.batteryPct == null ? '—' : `${Math.round(v.batteryPct)}%`}</span>
                    <span className="mono">{holdsOf(v.id) || '—'}</span>
                  </Row>
                ))}
              </Grid>
            )}
          </Panel>

          <Panel id="mon-move" title="수동 이동" aux="차량과 목표 노드를 골라 보냅니다" defaultOpen padded>
            <ManualMove map={map} vehicles={state} selected={props.selected} onSelect={props.onSelect}
              target={target} onTarget={setTarget} onDone={props.onRetry} moves={props.data.moves} />
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
                  placeNodes={props.data.map.nodes
                    .filter(n => n.enabled && layerOfNode(props.data.map, n.id) === selectedVehicle.vType)
                    .map(n => ({ id: n.id, code: n.code }))}
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

/** A two-position switch between the 2D plan and the 3D view. */
function ViewSwitch(props: { value: '2D' | '3D'; onChange: (v: '2D' | '3D') => void }) {
  const on = props.value === '3D';
  return (
    <button type="button" role="switch" aria-checked={on} aria-label="3D 보기"
      title={on ? '2D 평면도로 보기' : '3D로 보기'} onClick={() => props.onChange(on ? '2D' : '3D')}
      style={{
        position: 'relative', display: 'inline-grid', gridTemplateColumns: '1fr 1fr', alignItems: 'center',
        width: 74, height: 24, padding: 2, marginRight: 6, border: '1px solid var(--ln2)', borderRadius: 999,
        background: 'var(--gnd)', cursor: 'pointer', fontSize: 11, fontWeight: 600,
      }}>
      <span aria-hidden style={{
        position: 'absolute', top: 2, bottom: 2, left: on ? 'calc(50% + 1px)' : 2, width: 'calc(50% - 3px)',
        borderRadius: 999, background: 'var(--acc)', transition: 'left .18s ease',
      }} />
      <span style={{ position: 'relative', color: on ? 'var(--mut)' : '#fff' }}>2D</span>
      <span style={{ position: 'relative', color: on ? '#fff' : 'var(--mut)' }}>3D</span>
    </button>
  );
}

function VehicleDetail(props: {
  vehicle: Vehicle; reported: VehicleSnapshot | null; nodeCode: (id: number | null) => string;
  staleAfter: number; run: NodeHold[]; simulated?: boolean; onChanged?: () => void;
  /** Nodes of this vehicle's own network, so it is never placed on a line it cannot run on. */
  placeNodes?: { id: number; code: string }[];
}) {
  const r = props.reported;
  const [stateError, setStateError] = React.useState<string | null>(null);
  const [placeAt, setPlaceAt] = React.useState<number | ''>('');
  const [pickupFaulted, setPickupFaulted] = React.useState<number[]>([]);

  // Which vehicles are simulating a power-pickup failure, so a charge that keeps falling is
  // explained on the screen rather than looking like the control not working.
  React.useEffect(() => {
    if (!props.simulated) return;
    const c = new AbortController();
    get<number[]>(opsApi.simulatedPickupFaults(), c.signal).then(setPickupFaulted)
      .catch(cause => { if ((cause as Error)?.name !== 'AbortError') setPickupFaulted([]); });
    return () => c.abort();
  }, [props.simulated, props.reported]);
  // In simulation a person can put a vehicle into manual, pause it, fault it, or give it back to
  // the controller, the way a vehicle on site would report it. The simulated vehicle then holds.
  async function setState(mode: string, status: string) {
    if (!r) return;
    setStateError(null);
    try {
      // Only mode and status are sent: where the vehicle is and what it carries the simulator keeps.
      await send(opsApi.simulatedState(r.id), 'PUT', { mode, status }, '시뮬레이션 상태 변경');
      props.onChanged?.();
    } catch (cause) {
      setStateError(cause instanceof ApiError ? explainDecision(cause.message) : '바꾸지 못했습니다.');
    }
  }
  // Simulation only: set the state of charge, to watch charging requests and charge points at work.
  async function setBattery(batteryPct: number) {
    if (!r) return;
    setStateError(null);
    try {
      await send(opsApi.simulatedState(r.id), 'PUT', { batteryPct }, '시뮬레이션 배터리 변경');
      // Setting it by hand also stops a pickup fault, or the value would drop straight back.
      setPickupFaulted(f => f.filter(id => id !== r.id));
      props.onChanged?.();
    } catch (cause) {
      setStateError(cause instanceof ApiError ? explainDecision(cause.message) : '바꾸지 못했습니다.');
    }
  }
  /**
   * Puts the vehicle on a node outright. Refused while it has work, and then offered again as a
   * choice the person makes knowingly: moving it leaves the job somewhere, and that somewhere is
   * recovery, not nothing.
   */
  async function place(releaseWork = false) {
    if (!r || placeAt === '') return;
    setStateError(null);
    try {
      await send(opsApi.simulatedPlace(r.id), 'POST', { nodeId: placeAt, releaseWork }, '시뮬레이션 위치 지정');
      props.onChanged?.();
    } catch (cause) {
      if (cause instanceof ApiError && (cause.message === 'VEHICLE_HAS_ORDER' || cause.message === 'VEHICLE_ALREADY_MOVING')) {
        const work = cause.message === 'VEHICLE_HAS_ORDER' ? '진행 중인 반송 작업' : '진행 중인 이동';
        if (window.confirm(`${work}이 있습니다. 그래도 옮기면 작업은 복구 대기로 넘어갑니다 — `
          + `차량이 화물을 두고 움직인 셈이라 화물 위치를 사람이 확인해 기록해야 끝납니다.\n\n옮길까요?`)) {
          await place(true);
        }
        return;
      }
      setStateError(cause instanceof ApiError ? explainDecision(cause.message) : '옮기지 못했습니다.');
    }
  }
  async function setPickup(healthy: boolean) {
    if (!r) return;
    setStateError(null);
    try {
      await send(opsApi.simulatedPickup(r.id), 'PUT', { healthy }, '시뮬레이션 집전 상태 변경');
      setPickupFaulted(f => healthy ? f.filter(id => id !== r.id) : [...f, r.id]);
      props.onChanged?.();
    } catch (cause) {
      setStateError(cause instanceof ApiError ? explainDecision(cause.message) : '바꾸지 못했습니다.');
    }
  }
  const faulted = r != null && pickupFaulted.includes(r.id);
  const railFed = r != null && (r.chargingPolicy == null || r.chargingPolicy === 'DISABLED');
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
          <span className="hint" style={{ marginLeft: 6 }}>배터리</span>
          {[15, 35, 100].map(pct => (
            <button key={pct} type="button" style={tiny} disabled={!hasWriteToken()} onClick={() => setBattery(pct)}>{pct}%</button>
          ))}
          {/* Only a rail-fed vehicle has a pickup to fail. The charging kind runs its own cycle. */}
          {railFed && (
            <>
              <span className="hint" style={{ marginLeft: 6 }}>집전</span>
              <button type="button" style={faulted ? tinyOn : tiny} disabled={!hasWriteToken()}
                title={faulted
                  ? '레일에서 전력을 받지 못하는 상태입니다. 버퍼가 계속 떨어집니다.'
                  : '집전 고장을 넣습니다. 버퍼가 오르지 않고 계속 떨어져 알람 조건을 만듭니다.'}
                onClick={() => setPickup(faulted)}>
                {faulted ? '고장 중 · 해제' : '고장 주입'}
              </button>
            </>
          )}
          {stateError && <span className="hint" style={{ color: 'var(--crit)' }}>{stateError}</span>}
        </div>
      )}
      {props.simulated && r && (props.placeNodes?.length ?? 0) > 0 && (
        <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap', alignItems: 'center' }}>
          <span className="hint">위치 지정</span>
          <select style={{ ...tiny, minWidth: 92 }} value={placeAt} disabled={!hasWriteToken()}
            onChange={e => setPlaceAt(e.target.value === '' ? '' : Number(e.target.value))}>
            <option value="">노드</option>
            {props.placeNodes!.map(n => <option key={n.id} value={n.id}>{n.code}</option>)}
          </select>
          <button type="button" style={tiny} disabled={!hasWriteToken() || placeAt === ''}
            onClick={() => place()}>놓기</button>
          <span className="hint">
            주행이 아니라 그 자리에 놓습니다. 쥐고 있던 구간은 모두 반납합니다. 작업이나 이동 중이면 한 번 되묻고,
            그래도 옮기면 그 작업은 복구 대기로 넘어갑니다.
          </span>
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
/**
 * Battery shown red below the critical threshold, amber at or below the request threshold — but
 * only for a vehicle that carries its own charge. A vehicle fed from the rail has no thresholds to
 * be under, and colouring its buffer by them would be reading one number as if it were the other.
 */
function batteryColor(v: VehicleSnapshot, options: OperationOptions | null | undefined): string | undefined {
  if (v.batteryPct == null || !carriesItsOwnCharge(v)) return undefined;
  if (v.batteryPct < (options?.chargeCriticalPct ?? 20)) return 'var(--crit)';
  if (v.batteryPct <= (options?.chargeRequestPct ?? 40)) return '#B7791F';
  return undefined;
}
const carriesItsOwnCharge = (v: VehicleSnapshot) =>
  v.chargingPolicy != null && v.chargingPolicy !== 'DISABLED';
/**
 * The same column holds two different quantities, so it says which. A charge runs down as the
 * vehicle works and is put back on purpose; a buffer is kept full by the rail and only falls when
 * the vehicle is not being fed, which is a fault rather than a duty cycle.
 */
function chargeMeaning(v: VehicleSnapshot): string {
  if (v.batteryPct == null) return '보고된 값이 없습니다. 0%가 아니라 모른다는 뜻입니다.';
  return carriesItsOwnCharge(v)
    ? '차량이 들고 다니는 충전량입니다. 임계값 아래로 내려가면 충전을 우선합니다.'
    : '레일에서 급전받는 차량의 전력 버퍼입니다. 충전 주기가 없으므로 임계값이 아니라 계속 떨어지는지를 봅니다.';
}

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
  moves: VehicleMove[];
}) {
  const [busy, setBusy] = React.useState(false);
  const [notice, setNotice] = React.useState<{ tone: string; text: string } | null>(null);
  const vehicle = props.vehicles.find(v => v.id === props.selected) ?? null;
  const open = vehicle ? props.moves.find(m => m.vehicleId === vehicle.id) ?? null : null;
  const nodeCode = (id: number) => props.map.nodes.find(n => n.id === id)?.code ?? `#${id}`;

  async function stop() {
    if (!vehicle) return;
    setBusy(true); setNotice(null);
    try {
      const res = await send<{ decision: string }>(opsApi.stopMove(vehicle.id), 'POST', {}, '수동 이동 중지');
      setNotice({ tone: res.decision === 'MOVE_STOPPED' ? 'ok' : 'wr', text: explainDecision(res.decision) });
      props.onDone();
    } catch (cause) {
      setNotice({ tone: 'er', text: cause instanceof ApiError ? explainDecision(cause.message) : '중지하지 못했습니다.' });
    } finally { setBusy(false); }
  }
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
              : !hasWriteToken() ? 'Lv2 운전 이상으로 로그인해야 명령할 수 있습니다.' : null;

  async function toggleService() {
    if (!vehicle) return;
    const back = vehicle.outOfServiceSince != null;
    const reason = back ? null
      : window.prompt(`${vehicle.code}을(를) 왜 작업에서 빼는지 적어 주세요. 이유 없이 세워 둔 차량은 아무도 되돌리지 못합니다.`);
    if (!back && (reason === null || !reason.trim())) return;
    setBusy(true); setNotice(null);
    try {
      await send(opsApi.vehicleService(REVISION_ID, vehicle.id), 'PUT',
        { inService: back, reason }, back ? '서비스 복귀' : '서비스 제외');
      setNotice({ tone: 'ok', text: back ? '작업을 다시 받습니다.' : '작업을 받지 않습니다. 옮기려면 목표 노드를 골라 이동시키세요.' });
      props.onDone();
    } catch (cause) {
      setNotice({ tone: 'er', text: cause instanceof ApiError ? explainDecision(cause.message) : '처리하지 못했습니다.' });
    } finally { setBusy(false); }
  }

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
      {open && (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <Chip tone={open.stopRequestedAt ? 'wr' : 'ok'}>{open.stopRequestedAt ? '중지 중' : '이동 중'}</Chip>
          <span>{open.purpose === 'MANUAL' ? '수동 이동' : 'Zone 퇴출'} → {nodeCode(open.targetNodeId)}</span>
          {open.purpose === 'MANUAL' && !open.stopRequestedAt && (
            <button type="button" disabled={busy || !hasWriteToken()} onClick={stop} style={{
              border: '1px solid var(--crit)', background: 'var(--srf)', color: 'var(--crit)', borderRadius: 5,
              padding: '3px 12px', fontSize: 12, cursor: 'pointer' }}>이동 중지</button>
          )}
          {open.stopRequestedAt && <span className="hint">이미 받은 허가 끝에서 서면 이동을 닫습니다.</span>}
        </div>
      )}
      {/* Taking a vehicle out of service and moving it are two acts on purpose: marking it first is
          what stops the next dispatch picking it up while somebody decides where to put it. */}
      {vehicle && (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <button type="button" disabled={busy || !hasWriteToken()} onClick={toggleService} style={{
            border: `1px solid ${vehicle.outOfServiceSince ? 'var(--ln2)' : 'var(--warn)'}`,
            background: 'var(--srf)', color: vehicle.outOfServiceSince ? 'var(--ink2)' : '#8A5C05',
            borderRadius: 5, padding: '3px 12px', fontSize: 12,
            cursor: hasWriteToken() ? 'pointer' : 'default' }}>
            {vehicle.outOfServiceSince ? '서비스 복귀' : '서비스 제외'}
          </button>
          <span className="hint">
            {vehicle.outOfServiceSince
              ? `${vehicle.outOfServiceReason ?? ''}${vehicle.outOfServiceBy ? ` · ${vehicle.outOfServiceBy}` : ''}`
              : '작업을 주지 않게만 합니다. 옮기지는 않으니 정비 구역으로는 위에서 이동시키세요.'}
          </span>
        </div>
      )}
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
/** A simulation control that is currently doing something, so it is not mistaken for an offer. */
const tinyOn: React.CSSProperties = {
  ...tiny, borderColor: 'var(--warn)', background: '#FBF0DA', color: '#8A5C05',
};

createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>);
