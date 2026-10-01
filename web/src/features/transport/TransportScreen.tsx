import React from 'react';
import {
  ApiError, explainDecision, get, opsApi, send, hasWriteToken,
  type Assigned, type DeviceCommand, type Extension, type MapData,
  type TransportOrder, type VehicleSnapshot,
} from '../../api';
import { Chip, Empty } from '../../shell/States';

/**
 * Transport work as it is actually driven: create, assign, extend, cancel. Only work still going
 * on is listed here; finished orders are read in the history.
 *
 * Each action reports what it did in the system's own terms. Waiting for a vehicle, or for a
 * road to clear, is an ordinary answer here and is worded as one rather than as a failure.
 */
export function TransportScreen(props: {
  orders: TransportOrder[]; map: MapData; vehicles: VehicleSnapshot[];
  loadedAt: Date | null; onRetry: () => void;
}) {
  const [selected, setSelected] = React.useState<number | null>(null);
  const [notice, setNotice] = React.useState<{ tone: string; text: string } | null>(null);
  const [busy, setBusy] = React.useState(false);

  const portCode = (id: number) => props.map.ports.find(p => p.id === id)?.code ?? String(id);
  const vehicleCode = (id: number) => props.vehicles.find(v => v.id === id)?.code ?? `#${id}`;

  async function act(run: () => Promise<{ tone: string; text: string }>) {
    setBusy(true); setNotice(null);
    try { setNotice(await run()); props.onRetry(); }
    catch (cause) {
      setNotice({ tone: 'er', text: cause instanceof ApiError ? explainDecision(cause.message) : '요청을 처리하지 못했습니다.' });
    } finally { setBusy(false); }
  }

  const assign = (orderId: number) => act(async () => {
    const res = await send<Assigned>(opsApi.assign(orderId), 'POST', undefined, '운전자 배차');
    if (!res.created) return { tone: 'id', text: `작업 #${orderId}는 이미 ${vehicleCode(res.assignment.vehicleId)}를 잡고 있습니다.` };
    // A held vehicle with no command is the normal "assigned but cannot start" outcome.
    return res.commandId
      ? { tone: 'ok', text: `작업 #${orderId} → ${vehicleCode(res.assignment.vehicleId)} 배차, 이동 명령을 기록했습니다.` }
      : { tone: 'wr', text: `작업 #${orderId} → ${vehicleCode(res.assignment.vehicleId)} 배차했지만 출발 구간을 확보하지 못해 이동 명령은 아직 없습니다.` };
  });

  const cancel = (orderId: number) => act(async () => {
    const res = await send<TransportOrder>(opsApi.cancel(orderId), 'POST', undefined, '운전자 취소');
    return res.status === 'CANCEL_REQUESTED'
      ? { tone: 'wr', text: `작업 #${orderId} 취소를 요청했습니다. 이동 명령이 이미 나갔거나 화물을 실은 뒤라 장비가 멈춘 것을 확인해야 끝납니다.` }
      : { tone: 'ok', text: `작업 #${orderId}를 취소했습니다. 나가지 않은 이동 명령은 거둬들였습니다.` };
  });

  // Ending a recovery is a statement about where the carrier is, so it asks for that and for how
  // it was established; neither is guessed from the state the order was in.
  const recover = (orderId: number, carrierLocation: 'SOURCE' | 'DESTINATION') => {
    const where = carrierLocation === 'SOURCE' ? '출발지' : '목적지';
    const reason = window.prompt(`화물이 ${where}에 있음을 어떻게 확인했는지 적어 주세요.`);
    if (reason === null) return;
    void act(async () => {
      const res = await send<TransportOrder>(opsApi.recovery(orderId), 'POST', { carrierLocation, reason }, '복구 처리');
      return { tone: 'ok', text: `작업 #${orderId} 복구를 끝냈습니다 — 화물 ${where}, ${res.status === 'COMPLETED' ? '완료' : '취소'} 처리.` };
    });
  };

  const create = (draft: { source: string; destination: string; carrier: string; priority: number }) => act(async () => {
    // A fresh key per press: pressing again is a new order, a retry of the same press is not.
    const res = await send<TransportOrder>(opsApi.orders(), 'POST',
      { sourcePortCode: draft.source, destinationPortCode: draft.destination,
        carrierCode: draft.carrier || null, priority: draft.priority },
      '운전자 작업 생성', { 'Idempotency-Key': crypto.randomUUID() });
    return { tone: 'ok', text: `작업 #${res.id}를 접수했습니다 (${draft.source} → ${draft.destination}). 배차를 기다립니다.` };
  });

  const extendAll = () => act(async () => {
    const res = await send<Extension[]>(opsApi.extendAll(), 'POST', undefined, '이동 허가 연장');
    const extended = res.filter(e => e.decision === 'EXTENDED');
    if (res.length === 0) return { tone: 'id', text: '진행 중인 배정이 없습니다.' };
    if (extended.length === 0) {
      const reasons = [...new Set(res.map(e => explainDecision(e.decision)))].join(' / ');
      return { tone: 'id', text: `연장한 작업이 없습니다 — ${reasons}` };
    }
    return { tone: 'ok', text: `${extended.length}건 연장 — ${extended.map(e => `#${e.orderId} (+${e.addedNodeIds.length}구간)`).join(', ')}` };
  });

  return (
    <>
      <section className="card">
        <div className="ch">
          <h2>반송 작업</h2>
          <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
            <button type="button" onClick={extendAll} disabled={busy || !hasWriteToken()} style={ghost}>
              이동 허가 연장
            </button>
            <Refreshed at={props.loadedAt} onRetry={props.onRetry} />
          </div>
        </div>

        {notice && (
          <div className="cb" style={{ paddingBottom: 0 }}>
            <p style={{ margin: 0, fontSize: 12 }}>
              <Chip tone={notice.tone}>{notice.tone === 'er' ? '거부' : notice.tone === 'ok' ? '완료' : '안내'}</Chip>
              {' '}{notice.text}
            </p>
          </div>
        )}
        {!hasWriteToken() && (
          <div className="cb" style={{ paddingBottom: 0 }}>
            <p className="hint" style={{ margin: 0 }}>조회는 열려 있고 상태를 바꾸는 요청만 토큰을 요구합니다. 설정 화면에서 토큰을 입력하면 배차·취소·연장이 열립니다.</p>
          </div>
        )}

        <CreateOrder map={props.map} busy={busy} onCreate={create} />

        {props.orders.length === 0
          ? <Empty title="진행 중인 반송 작업이 없습니다" hint="위에서 작업을 만들면 대기 상태로 나타납니다. 끝난 작업은 History에서 봅니다." />
          : (
            <div className="tbl-scroll">
              <table className="t">
                <thead>
                  <tr><th>번호</th><th>상태</th><th>경로</th><th>차종</th><th>캐리어</th><th className="mono">우선순위</th><th>조작</th></tr>
                </thead>
                <tbody>
                  {props.orders.map(o => (
                    <tr key={o.id} onClick={() => setSelected(o.id === selected ? null : o.id)}
                      style={{ cursor: 'pointer', background: o.id === selected ? 'var(--acc-soft)' : undefined }}>
                      <td className="mono">{o.id}</td>
                      <td><Chip tone={toneOf(o.status)}>{o.status}</Chip></td>
                      <td className="mono">{portCode(o.sourcePortId)} → {portCode(o.destinationPortId)}</td>
                      <td className="mono">{o.vehicleType}</td>
                      <td className="mono">{o.carrierCode ?? '—'}</td>
                      <td className="mono">{o.priority}</td>
                      <td onClick={e => e.stopPropagation()}>
                        <span style={{ display: 'flex', gap: 6 }}>
                          <button type="button" style={ghost} disabled={busy || !hasWriteToken() || o.status !== 'QUEUED'}
                            title={o.status === 'QUEUED' ? undefined : '대기 중인 작업만 배차할 수 있습니다.'}
                            onClick={() => assign(o.id)}>배차</button>
                          <button type="button" style={ghost} disabled={busy || !hasWriteToken() || TERMINAL.has(o.status)}
                            title={TERMINAL.has(o.status) ? '이미 끝난 작업입니다.' : undefined}
                            onClick={() => cancel(o.id)}>취소</button>
                          {o.status === 'RECOVERY_REQUIRED' && <>
                            <button type="button" style={ghost} disabled={busy || !hasWriteToken()}
                              title="화물이 출발지에 있음을 확인했으면 작업을 취소로 끝냅니다."
                              onClick={() => recover(o.id, 'SOURCE')}>출발지 확인</button>
                            <button type="button" style={ghost} disabled={busy || !hasWriteToken()}
                              title="화물이 목적지에 있음을 확인했으면 작업을 완료로 끝냅니다."
                              onClick={() => recover(o.id, 'DESTINATION')}>목적지 확인</button>
                          </>}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
      </section>

      {selected !== null && <OrderDetail orderId={selected} map={props.map} vehicles={props.vehicles} />}
    </>
  );
}

/**
 * A new order from the screen. Ports are offered by access vehicle type: a destination is offered
 * only when the same kind of vehicle can reach it, which is what the server would insist on anyway.
 */
function CreateOrder(props: {
  map: MapData; busy: boolean;
  onCreate: (draft: { source: string; destination: string; carrier: string; priority: number }) => void;
}) {
  const [source, setSource] = React.useState('');
  const [destination, setDestination] = React.useState('');
  const [carrier, setCarrier] = React.useState('');
  const [priority, setPriority] = React.useState(50);
  const sourcePort = props.map.ports.find(p => p.code === source);
  const destinations = props.map.ports.filter(p => p.code !== source && (!sourcePort || p.accessVehicleType === sourcePort.accessVehicleType));
  const ready = source && destination && hasWriteToken() && priority >= 0 && priority <= 100;
  const field: React.CSSProperties = { border: '1px solid var(--ln2)', borderRadius: 5, padding: '3px 6px', fontSize: 12, background: 'var(--srf)', color: 'var(--ink)' };
  return (
    <div className="cb" style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', borderBottom: '1px solid var(--ln)', fontSize: 12 }}>
      <b>작업 생성</b>
      <label>출발 <select style={field} value={source} onChange={e => { setSource(e.target.value); setDestination(''); }}>
        <option value="">선택</option>
        {props.map.ports.map(p => <option key={p.id} value={p.code}>{p.code} · {p.accessVehicleType}</option>)}
      </select></label>
      <label>도착 <select style={field} value={destination} onChange={e => setDestination(e.target.value)} disabled={!source}>
        <option value="">선택</option>
        {destinations.map(p => <option key={p.id} value={p.code}>{p.code} · {p.accessVehicleType}</option>)}
      </select></label>
      <label>캐리어 <input style={{ ...field, width: 110 }} value={carrier} placeholder="선택 입력" onChange={e => setCarrier(e.target.value)} /></label>
      <label>우선순위 <input type="number" min={0} max={100} style={{ ...field, width: 60 }} value={priority}
        onChange={e => setPriority(Number(e.target.value))} /></label>
      <button type="button" disabled={props.busy || !ready} onClick={() => props.onCreate({ source, destination, carrier, priority })}
        style={{ border: '1px solid var(--acc)', background: ready ? 'var(--acc)' : 'var(--srf)', color: ready ? '#fff' : 'var(--mut)',
          borderRadius: 5, padding: '3px 14px', fontSize: 12, cursor: ready ? 'pointer' : 'default' }}>생성</button>
      {!hasWriteToken() && <span className="hint">토큰을 입력해야 만들 수 있습니다.</span>}
    </div>
  );
}

/** Commands issued for one order, which is where "assigned" becomes something a vehicle was told. */
function OrderDetail({ orderId, map, vehicles }: { orderId: number; map: MapData; vehicles: VehicleSnapshot[] }) {
  const [commands, setCommands] = React.useState<DeviceCommand[] | null>(null);
  const [error, setError] = React.useState<unknown>(null);

  React.useEffect(() => {
    const controller = new AbortController();
    setCommands(null); setError(null);
    get<DeviceCommand[]>(opsApi.orderCommands(orderId), controller.signal)
      .then(setCommands)
      .catch(cause => { if ((cause as Error).name !== 'AbortError') setError(cause); });
    return () => controller.abort();
  }, [orderId]);

  return (
    <section className="card">
      <div className="ch"><h2>작업 #{orderId} 장비 명령</h2><span className="aux">배정은 차량을 잡는 것이고, 명령은 차량에 알리는 것입니다</span></div>
      {error ? <Empty title="명령을 불러오지 못했습니다" hint="잠시 후 다시 시도해 주세요." />
        : commands === null ? <div className="cb"><p className="hint" style={{ margin: 0 }}>불러오는 중…</p></div>
          : commands.length === 0
            ? <Empty title="이 작업에 기록된 장비 명령이 없습니다"
                hint="차량을 잡았더라도 출발 구간을 확보하지 못하면 이동 명령은 만들어지지 않습니다." />
            : (
              <div className="tbl-scroll">
                <table className="t">
                  <thead><tr><th>명령</th><th>종류</th><th>상태</th><th className="mono">시도</th><th>허가 구간</th><th>비고</th></tr></thead>
                  <tbody>
                    {commands.map(c => {
                      const authorized = authorizedNodes(c.payload).map(id => nodeCode(map, id));
                      return (
                        <tr key={c.id}>
                          <td className="mono">{c.commandId}</td>
                          <td className="mono">{c.commandType}</td>
                          <td><Chip tone={statusTone(c.status)}>{c.status}</Chip></td>
                          <td className="mono">{c.attempt}</td>
                          <td className="mono">{authorized.length ? authorized.join(' › ') : '—'}</td>
                          <td className="mono">{c.lastError ?? c.lastOutcome ?? '—'}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
      <p className="hint" style={{ padding: '0 15px 14px' }}>
        표시된 허가 구간은 차량이 점유해도 되는 범위입니다. 계획 경로 전체가 아니라 교통이 실제로 내준 만큼만
        명령에 실립니다. {vehicles.length === 0 ? '' : ''}
      </p>
    </section>
  );
}

/** The payload is the adapter's own shape; only the authorized run is read back for display. */
function authorizedNodes(payload: string): number[] {
  try {
    const parsed = JSON.parse(payload) as { authorizedNodeIds?: number[] };
    return Array.isArray(parsed.authorizedNodeIds) ? parsed.authorizedNodeIds : [];
  } catch { return []; }
}

const TERMINAL = new Set(['COMPLETED', 'CANCELLED', 'FAILED']);
function nodeCode(map: MapData, id: number) { return map.nodes.find(n => n.id === id)?.code ?? String(id); }
function statusTone(s: string) {
  if (s === 'ACCEPTED') return 'ok';
  if (s === 'FAILED') return 'er';
  if (s === 'PENDING' || s === 'WITHDRAWN') return 'id';
  return 'wr';
}
function toneOf(status: string): string {
  if (status === 'COMPLETED') return 'ok';
  if (status === 'FAILED' || status === 'RECOVERY_REQUIRED') return 'er';
  if (status === 'CANCELLED' || status === 'CANCEL_REQUESTED') return 'wr';
  if (status === 'QUEUED') return 'id';
  return 'ac';
}

function Refreshed({ at, onRetry }: { at: Date | null; onRetry: () => void }) {
  return (
    <span className="aux">
      최근 조회 <span className="mono">{at ? at.toLocaleTimeString('ko-KR', { hour12: false }) : '—'}</span>
      {' '}<button type="button" onClick={onRetry} style={ghost}>새로고침</button>
    </span>
  );
}

const ghost: React.CSSProperties = {
  border: '1px solid var(--ln2)', background: 'var(--srf)', color: 'var(--ink2)',
  borderRadius: 4, padding: '3px 10px', fontSize: 11, cursor: 'pointer',
};
