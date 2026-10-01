import React from 'react';
import { api, get, type MapData, type OrderEvent, type TransportOrder } from '../../api';
import { Chip, Empty } from '../../shell/States';

/**
 * Finished work: completed, cancelled and failed orders, newest first, and the event log of the one
 * picked. The work list shows only what is still going on; this is where the rest is read.
 */
export function HistoryScreen({ map }: { map: MapData }) {
  const [orders, setOrders] = React.useState<TransportOrder[] | null>(null);
  const [selected, setSelected] = React.useState<number | null>(null);
  const [events, setEvents] = React.useState<OrderEvent[] | null>(null);
  const [filter, setFilter] = React.useState<'ALL' | 'COMPLETED' | 'CANCELLED' | 'FAILED'>('ALL');

  React.useEffect(() => {
    const c = new AbortController();
    get<TransportOrder[]>(api.finishedOrders(300), c.signal).then(setOrders)
      .catch(cause => { if ((cause as Error).name !== 'AbortError') setOrders([]); });
    return () => c.abort();
  }, []);
  React.useEffect(() => {
    if (selected === null) { setEvents(null); return; }
    const c = new AbortController();
    setEvents(null);
    get<OrderEvent[]>(api.orderEvents(selected), c.signal).then(setEvents)
      .catch(cause => { if ((cause as Error).name !== 'AbortError') setEvents([]); });
    return () => c.abort();
  }, [selected]);

  const portCode = (id: number) => map.ports.find(p => p.id === id)?.code ?? String(id);
  const shown = (orders ?? []).filter(o => filter === 'ALL' || o.status === filter);
  const time = (t: string | null | undefined) => t ? new Date(t).toLocaleString('ko-KR', { hour12: false }) : '—';

  return (
    <>
      <section className="card">
        <div className="ch">
          <h2>끝난 작업</h2>
          <div style={{ marginLeft: 'auto', display: 'flex', gap: 5 }}>
            {(['ALL', 'COMPLETED', 'CANCELLED', 'FAILED'] as const).map(f => (
              <button key={f} type="button" aria-pressed={filter === f} onClick={() => setFilter(f)} style={{
                border: '1px solid var(--ln2)', borderRadius: 5, padding: '3px 10px', fontSize: 11, cursor: 'pointer',
                background: filter === f ? 'var(--acc)' : 'var(--srf)', color: filter === f ? '#fff' : 'var(--ink2)' }}>
                {f === 'ALL' ? '전체' : f === 'COMPLETED' ? '완료' : f === 'CANCELLED' ? '취소' : '실패'}
              </button>
            ))}
          </div>
        </div>
        {orders === null ? <div className="cb"><p className="hint" style={{ margin: 0 }}>불러오는 중…</p></div>
          : shown.length === 0 ? <Empty title="끝난 작업이 없습니다" />
            : (
              <div className="tbl-scroll">
                <table className="t">
                  <thead><tr><th>번호</th><th>결과</th><th>경로</th><th>캐리어</th><th>화물 위치</th><th>접수</th><th>종료</th></tr></thead>
                  <tbody>
                    {shown.map(o => (
                      <tr key={o.id} onClick={() => setSelected(o.id === selected ? null : o.id)}
                        style={{ cursor: 'pointer', background: o.id === selected ? 'var(--acc-soft)' : undefined }}>
                        <td className="mono">{o.id}</td>
                        <td><Chip tone={o.status === 'COMPLETED' ? 'ok' : o.status === 'FAILED' ? 'er' : 'wr'}>{o.status}</Chip></td>
                        <td className="mono">{portCode(o.sourcePortId)} → {portCode(o.destinationPortId)}</td>
                        <td className="mono">{o.carrierCode ?? '—'}</td>
                        <td className="mono">{o.carrierLocation ?? '—'}</td>
                        <td className="mono">{time(o.createdAt)}</td>
                        <td className="mono">{time(o.terminalAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
      </section>

      {selected !== null && (
        <section className="card">
          <div className="ch"><h2>작업 #{selected} 이력</h2><span className="aux">상태가 바뀐 순서와 사유, 조작 주체</span></div>
          {events === null ? <div className="cb"><p className="hint" style={{ margin: 0 }}>불러오는 중…</p></div>
            : events.length === 0 ? <Empty title="이력이 없습니다" />
              : (
                <div className="tbl-scroll">
                  <table className="t">
                    <thead><tr><th>#</th><th>시각</th><th>전이</th><th>사유</th><th>주체</th></tr></thead>
                    <tbody>
                      {events.map(e => (
                        <tr key={e.seq}>
                          <td className="mono">{e.seq}</td>
                          <td className="mono">{time(e.occurredAt)}</td>
                          <td className="mono">{e.oldStatus ?? '—'} → {e.newStatus}</td>
                          <td>{e.reason ?? '—'}</td>
                          <td className="mono">{e.actorCode ?? '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
        </section>
      )}
    </>
  );
}
