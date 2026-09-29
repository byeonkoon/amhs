import React from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';
import { api, get, SITE_ID, type MapData, type TransportOrder, type Vehicle } from './api';
import { AppShell, type MenuKey, type ServerState } from './shell/AppShell';
import { Chip, Empty, Failed, freshnessOf, Loading } from './shell/States';
import { MapLegend, MapView } from './features/map/MapView';

const REVISION_ID = 1;

type Loaded = { map: MapData; vehicles: Vehicle[]; orders: TransportOrder[] };

function App() {
  const [menu, setMenu] = React.useState<MenuKey>('fleet');
  const [data, setData] = React.useState<Loaded | null>(null);
  const [error, setError] = React.useState<unknown>(null);
  const [selected, setSelected] = React.useState<Vehicle | null>(null);
  const [layer, setLayer] = React.useState<'all' | 'OHT' | 'AGV'>('all');
  const [reload, setReload] = React.useState(0);
  const [loadedAt, setLoadedAt] = React.useState<Date | null>(null);

  React.useEffect(() => {
    const controller = new AbortController();
    setError(null); setData(null);
    Promise.all([
      get<MapData>(api.map(REVISION_ID), controller.signal),
      get<Vehicle[]>(api.vehicles(), controller.signal),
      // The transport list is optional here: A06 owns the ledger, this screen only previews it.
      get<TransportOrder[]>(api.transportOrders(), controller.signal).catch(() => [] as TransportOrder[]),
    ])
      .then(([map, vehicles, orders]) => { setData({ map, vehicles, orders }); setLoadedAt(new Date()); })
      .catch(cause => { if ((cause as Error).name !== 'AbortError') setError(cause); });
    return () => controller.abort();
  }, [reload]);

  const server: ServerState = error ? 'unreachable' : data ? 'connected' : 'connecting';
  const retry = () => setReload(v => v + 1);
  const page = PAGES[menu];

  return (
    <AppShell active={menu} onSelect={k => { setMenu(k); setSelected(null); }}
      crumb={['Home', page.crumb, page.title]} title={page.title} subtitle={page.subtitle} server={server}>
      {error ? <div className="card"><Failed error={error} onRetry={retry} /></div>
        : !data ? <div className="card"><Loading what={page.title} /></div>
          : menu === 'transport'
            ? <TransportScreen orders={data.orders} map={data.map} loadedAt={loadedAt} onRetry={retry} />
            : <FleetScreen data={data} selected={selected} onSelect={setSelected}
                layer={layer} onLayer={setLayer} loadedAt={loadedAt} onRetry={retry} />}
    </AppShell>
  );
}

const PAGES: Record<MenuKey, { title: string; subtitle: string; crumb: string }> = {
  overview: { title: '개요', subtitle: 'Overview · Status / KPI', crumb: 'Overview' },
  fleet: { title: '지도·차량 현황', subtitle: 'Fleet · Map / Vehicles', crumb: 'Fleet' },
  equipment: { title: '설비', subtitle: 'Equipment · Stations / Ports', crumb: 'Equipment' },
  transport: { title: '반송 작업', subtitle: 'Transport · Job / Route', crumb: 'Transport' },
  inventory: { title: '재고', subtitle: 'Inventory · Carrier / Stocker', crumb: 'Inventory' },
  alarms: { title: '알람', subtitle: 'Alarms · Event / Notification', crumb: 'Alarms' },
  history: { title: '이력', subtitle: 'History · Log / Trace', crumb: 'History' },
  diagnostics: { title: '진단', subtitle: 'Diagnostics · Health / Recovery', crumb: 'Diagnostics' },
};

function Refreshed({ at, onRetry }: { at: Date | null; onRetry: () => void }) {
  return (
    <span className="aux">
      최근 조회 <span className="mono">{at ? at.toLocaleTimeString('ko-KR', { hour12: false }) : '—'}</span>
      {' '}<button type="button" onClick={onRetry}
        style={{ border: '1px solid var(--ln2)', background: 'var(--srf)', borderRadius: 4, padding: '2px 8px', cursor: 'pointer' }}>
        새로고침
      </button>
    </span>
  );
}

function FleetScreen(props: {
  data: Loaded; selected: Vehicle | null; onSelect: (v: Vehicle) => void;
  layer: 'all' | 'OHT' | 'AGV'; onLayer: (l: 'all' | 'OHT' | 'AGV') => void;
  loadedAt: Date | null; onRetry: () => void;
}) {
  const { map, vehicles } = props.data;
  const node = (id: number) => map.nodes.find(n => n.id === id);
  const oht = vehicles.filter(v => v.vType === 'OHT').length;
  const agv = vehicles.filter(v => v.vType === 'AGV').length;

  return (
    <>
      <div className="grid g4">
        <Kpi k="지도 revision" v={`REV ${map.revisionId}`} d={`노드 ${map.nodes.length} · 구간 ${map.edges.length}`} />
        <Kpi k="포트" v={map.ports.length} d="접근 차종별 등록" />
        <Kpi k="OHT" v={oht} d="천장 레일" />
        <Kpi k="AGV" v={agv} d="바닥 주행로" />
      </div>

      <div className="grid g-map">
        <section className="card">
          <div className="ch">
            <h2>설비 레이아웃</h2>
            <div style={{ display: 'flex', gap: 6, marginLeft: 'auto', alignItems: 'center' }}>
              <span style={{ fontSize: 11, color: 'var(--mut)' }}>레이어</span>
              {(['all', 'OHT', 'AGV'] as const).map(l => (
                <button key={l} type="button" onClick={() => props.onLayer(l)}
                  aria-pressed={props.layer === l}
                  style={{
                    border: '1px solid var(--ln2)', borderRadius: 5, padding: '4px 11px', fontSize: 11, cursor: 'pointer',
                    background: props.layer === l ? 'var(--acc)' : 'var(--srf)',
                    color: props.layer === l ? '#fff' : 'var(--ink2)',
                  }}>
                  {l === 'all' ? '전체' : l}
                </button>
              ))}
            </div>
          </div>
          {map.nodes.length === 0
            ? <Empty title="등록된 지도 노드가 없습니다" hint="이 지도 revision에 노드가 없습니다. 지도 설정을 확인해 주세요." />
            : <MapView map={map} vehicles={vehicles} selectedId={props.selected?.id ?? null}
                onSelect={props.onSelect} layer={props.layer} />}
          <MapLegend />
        </section>

        <div style={{ display: 'grid', gap: 14, alignContent: 'start' }}>
          <section className="card">
            <div className="ch"><h2>차량</h2><Refreshed at={props.loadedAt} onRetry={props.onRetry} /></div>
            <div className="cb">
              {vehicles.length === 0
                ? <Empty title="등록된 차량이 없습니다" hint="차량 설정이 아직 없습니다." />
                : vehicles.map(v => {
                  const f = freshnessOf(v.positionSource, v.lastReceivedAt);
                  return (
                    <button key={v.id} type="button"
                      className={`vehicle-btn${props.selected?.id === v.id ? ' sel' : ''}`}
                      onClick={() => props.onSelect(v)}>
                      <b>{v.code}</b>
                      <span>{v.vType} · {node(v.currentNodeId)?.code ?? '위치 불명'} · </span>
                      <Chip tone={f.chip}>{f.label}</Chip>
                    </button>
                  );
                })}
            </div>
          </section>

          <section className="card">
            <div className="ch"><h2>차량 상세</h2></div>
            <div className="cb">
              {!props.selected
                ? <p className="hint" style={{ margin: 0 }}>지도나 목록에서 차량을 선택하면 상세가 표시됩니다.</p>
                : <VehicleDetail vehicle={props.selected} nodeCode={node(props.selected.currentNodeId)?.code} />}
            </div>
          </section>
        </div>
      </div>

      <section className="card">
        <div className="ch"><h2>포트 접근 조건</h2><span className="aux">출발·도착 양쪽 차종이 같아야 단일 차량 작업이 됩니다</span></div>
        <div className="tbl-scroll">
          {map.ports.length === 0 ? <Empty title="등록된 포트가 없습니다" /> : (
            <table className="t">
              <thead><tr><th>포트</th><th>종류</th><th>접근 차종</th><th>연결 노드</th></tr></thead>
              <tbody>
                {map.ports.map(p => (
                  <tr key={p.id}>
                    <td className="mono">{p.code}</td>
                    <td>{p.portType}</td>
                    <td><Chip tone={p.accessVehicleType === 'OHT' ? 'ac' : 'id'}>{p.accessVehicleType}</Chip></td>
                    <td className="mono">{node(p.linkedNodeId)?.code ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </section>
    </>
  );
}

function VehicleDetail({ vehicle, nodeCode }: { vehicle: Vehicle; nodeCode?: string }) {
  const f = freshnessOf(vehicle.positionSource, vehicle.lastReceivedAt);
  return (
    <>
      <dl className="kv">
        <dt>차량</dt><dd className="mono">{vehicle.code}</dd>
        <dt>종류</dt><dd className="mono">{vehicle.vType}</dd>
        <dt>위치</dt><dd className="mono">{nodeCode ?? '불명'}</dd>
        <dt>위치 근거</dt><dd><Chip tone={f.chip}>{f.label}</Chip></dd>
        <dt>연결 상태</dt><dd><Chip tone={vehicle.state === 'NOT_CONNECTED' ? 'wr' : 'ok'}>{vehicle.state}</Chip></dd>
        <dt>배차</dt><dd><Chip tone={vehicle.assignmentEnabled ? 'ok' : 'id'}>{vehicle.assignmentEnabled ? '허용' : '비활성'}</Chip></dd>
      </dl>
      {f.kind === 'configured' && (
        <p className="hint">표시된 위치는 설정값이며 장비가 보고한 실시간 위치가 아닙니다. 장비 어댑터 연결 후 실제 수신 위치로 바뀝니다.</p>
      )}
    </>
  );
}

function TransportScreen(props: { orders: TransportOrder[]; map: MapData; loadedAt: Date | null; onRetry: () => void }) {
  const portCode = (id: number) => props.map.ports.find(p => p.id === id)?.code ?? String(id);
  return (
    <section className="card">
      <div className="ch"><h2>반송 작업</h2><Refreshed at={props.loadedAt} onRetry={props.onRetry} /></div>
      {props.orders.length === 0
        ? <Empty title="접수된 반송 작업이 없습니다" hint="작업이 접수되면 대기 상태로 이 목록에 나타납니다." />
        : (
          <div className="tbl-scroll">
            <table className="t">
              <thead><tr><th>번호</th><th>상태</th><th>경로</th><th>차종</th><th>캐리어</th><th className="mono">우선순위</th></tr></thead>
              <tbody>
                {props.orders.map(o => (
                  <tr key={o.id}>
                    <td className="mono">{o.id}</td>
                    <td><Chip tone={toneOf(o.status)}>{o.status}</Chip></td>
                    <td className="mono">{portCode(o.sourcePortId)} → {portCode(o.destinationPortId)}</td>
                    <td className="mono">{o.vehicleType}</td>
                    <td className="mono">{o.carrierCode ?? '—'}</td>
                    <td className="mono">{o.priority}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      <p className="hint" style={{ padding: '0 15px 14px' }}>
        현재는 접수된 작업의 조회만 제공합니다. 배차·실행과 실시간 갱신은 후속 작업에서 연결합니다.
      </p>
    </section>
  );
}

function toneOf(status: string): string {
  if (status === 'COMPLETED') return 'ok';
  if (status === 'FAILED' || status === 'RECOVERY_REQUIRED') return 'er';
  if (status === 'CANCELLED' || status === 'CANCEL_REQUESTED') return 'wr';
  if (status === 'QUEUED') return 'id';
  return 'ac';
}

function Kpi({ k, v, d }: { k: string; v: React.ReactNode; d: string }) {
  return <div className="kpi"><div className="k">{k}</div><div className="v">{v}</div><div className="d">{d}</div></div>;
}

createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>);
