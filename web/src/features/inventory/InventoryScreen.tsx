import React from 'react';
import {
  ApiError, get, invApi, send,
  type CellView, type InventorySummary, type MapData,
  type Placement, type Session, type StoragePlace, type TransportOrder,
} from '../../api';
import { Chip, Empty } from '../../shell/States';
import { Grid, Panel, Row } from '../../shell/Panel';
import { aborted } from '../equipment/EquipmentScreen';

/**
 * What the site is holding, and how well anyone knows where.
 *
 * <p>Three different facts about a cell are kept apart here, because showing them as one is how a
 * cell being kept empty on purpose becomes a cell that is simply full:
 * <ul>
 *   <li>a carrier is in it — a fact about now, recorded by a person who looked;</li>
 *   <li>it is marked not to be filled — a decision, with the reason it was made;</li>
 *   <li>a carrier is in transport — the order says where it is, and this screen does not.</li>
 * </ul>
 *
 * <p>A carrier recorded at a port with no cell is shown as its own state, not as a gap. The
 * controller meets equipment at a port and cannot see inside it, so "it went in there and nobody
 * wrote down where" is the honest answer and a different problem from "nobody knows where it is".
 */
export function InventoryScreen({ session, map, orders }:
  { session: Session; map: MapData; orders: TransportOrder[] }) {
  const [summary, setSummary] = React.useState<InventorySummary | null>(null);
  const [placements, setPlacements] = React.useState<Placement[] | null>(null);
  const [places, setPlaces] = React.useState<StoragePlace[]>([]);
  const [rackId, setRackId] = React.useState<number | null>(null);
  const [cells, setCells] = React.useState<CellView[]>([]);
  const [notice, setNotice] = React.useState<{ tone: string; text: string } | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [reload, setReload] = React.useState(0);
  const [carrier, setCarrier] = React.useState('');

  const mayChange = session.allows.includes('inventory');

  React.useEffect(() => {
    const c = new AbortController();
    get<Placement[]>(invApi.placements(), c.signal).then(setPlacements)
      .catch(cause => { if (!aborted(cause)) setPlacements([]); });
    get<InventorySummary>(invApi.summary(), c.signal).then(setSummary)
      .catch(cause => { if (!aborted(cause)) setSummary(null); });
    // Places, not equipment: the screen opens on somewhere that actually has shelves.
    get<StoragePlace[]>(invApi.places(), c.signal)
      .then(list => { setPlaces(list); setRackId(id => list.some(p => p.rackId === id) ? id : list[0]?.rackId ?? null); })
      .catch(cause => { if (!aborted(cause)) setPlaces([]); });
    return () => c.abort();
  }, [reload]);

  const place = places.find(p => p.rackId === rackId) ?? null;

  React.useEffect(() => {
    if (!place) { setCells([]); return; }
    const c = new AbortController();
    get<CellView[]>(invApi.cells(place.unitId, place.rackId), c.signal).then(setCells)
      .catch(cause => { if (!aborted(cause)) setCells([]); });
    return () => c.abort();
  }, [place?.unitId, place?.rackId, reload]);

  async function act(run: () => Promise<unknown>, done: string) {
    setBusy(true); setNotice(null);
    try { await run(); setReload(v => v + 1); setNotice({ tone: 'ok', text: done }); }
    catch (cause) {
      setNotice({ tone: 'er', text: cause instanceof ApiError ? explain(cause.message) : '처리하지 못했습니다.' });
    } finally { setBusy(false); }
  }

  /** Recording a carrier into a cell, which is also how it is moved to another one. */
  const intoCell = (cell: CellView) => {
    if (!place) return;
    const code = carrier.trim();
    if (!code) { setNotice({ tone: 'er', text: '먼저 화물 ID를 입력하세요. 어떤 화물인지 없이 칸만 고를 수는 없습니다.' }); return; }
    const reason = window.prompt(`${code}이(가) ${cell.code}에 있다는 것을 어떻게 확인했는지 적어 주세요.`);
    if (reason === null || !reason.trim()) return;
    act(() => send<Placement>(invApi.placement(code), 'PUT',
      { unitId: place.unitId, rackId: place.rackId, slotId: cell.slotId, reason }, '화물 위치 기록'),
      `${code}을(를) ${cell.code}에 기록했습니다.`);
  };

  const atPort = (portId: number, portLabel: string) => {
    const code = carrier.trim();
    if (!code) { setNotice({ tone: 'er', text: '먼저 화물 ID를 입력하세요.' }); return; }
    const reason = window.prompt(`${code}을(를) ${portLabel}에서 설비가 받아 갔다는 것을 어떻게 확인했는지 적어 주세요.`);
    if (reason === null || !reason.trim()) return;
    act(() => send<Placement>(invApi.placement(code), 'PUT', { portId, reason }, '포트 인계 기록'),
      `${code}을(를) ${portLabel} 인계로 기록했습니다. 셀은 미지정입니다.`);
  };

  const forget = (p: Placement) => {
    const reason = window.prompt(`${p.carrierCode}이(가) 왜 더 이상 여기 없는지 적어 주세요.`);
    if (reason === null || !reason.trim()) return;
    act(() => send<{ removed: boolean }>(
      `${invApi.placement(p.carrierCode)}?reason=${encodeURIComponent(reason)}`, 'DELETE', {}, '화물 위치 삭제'),
      `${p.carrierCode}의 위치 기록을 지웠습니다.`);
  };

  const inTransport = orders
    .filter(o => o.carrierCode && !TERMINAL.has(o.status))
    .map(o => ({ code: o.carrierCode as string, status: o.status, where: o.carrierLocation ?? 'UNKNOWN' }));
  const storagePorts = map.ports.filter(p => p.equipmentId != null);

  if (placements === null) return <section className="card"><div className="cb"><p className="hint" style={{ margin: 0 }}>불러오는 중…</p></div></section>;

  return (
    <>
      <div className="statbar">
        <span className="s"><span className="k">보관 중</span><span className="v">{summary?.stored ?? placements.length}</span></span>
        <span className="s"><span className="k">셀에 기록</span><span className="v">{summary?.inCells ?? 0}</span></span>
        <span className="s"><span className="k">셀 미지정</span>
          <span className="v" style={(summary?.atPortOnly ?? 0) > 0 ? { color: 'var(--warn)' } : undefined}>
            {summary?.atPortOnly ?? 0}</span>
          <span className="d">{(summary?.atPortOnly ?? 0) > 0 ? '포트 인계만 확인됨' : ''}</span></span>
        <span className="s"><span className="k">반송 중</span><span className="v">{summary?.inTransport ?? inTransport.length}</span>
          <span className="d">작업이 위치를 정합니다</span></span>
        <span className="s"><span className="k">셀</span><span className="v">{summary?.cells ?? 0}</span>
          <span className="d">사용 금지 {summary?.blockedCells ?? 0}</span></span>
        <span className="s"><span className="k">기록</span><span className="v">{mayChange ? '가능' : '불가'}</span>
          <span className="d">{mayChange ? '' : 'Lv3 운영 관리 권한이 필요합니다'}</span></span>
      </div>

      {notice && <div className="card"><div className="cb" style={{ padding: '10px 14px' }}>
        <p style={{ margin: 0, fontSize: 12 }}>
          <Chip tone={notice.tone}>{notice.tone === 'er' ? '거부' : '완료'}</Chip> {notice.text}
        </p>
      </div></div>}

      <div className="grid g-mon">
        <div style={{ display: 'grid', gap: 9, alignContent: 'start' }}>
          <Panel id="inv-stored" title="보관 중인 화물" aux={`${placements.length}개`} defaultOpen>
            {placements.length === 0
              ? <Empty title="기록된 화물이 없습니다"
                  hint="설비 안의 위치는 설비가 알려 주지 않습니다. 확인한 사람이 기록해야 남습니다." />
              : (
                <Grid cols="1fr 150px 70px 58px" head={['화물', '위치', '확인', '']}>
                  {placements.map(p => (
                    <Row key={p.id}>
                      <span className="mono">{p.carrierCode}</span>
                      <span title={placeOf(p)}
                        style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {p.slotCode
                          ? <span className="mono">{p.slotCode}</span>
                          : <><Chip tone="wr">셀 미지정</Chip> <span className="mono" style={{ fontSize: 10 }}>{p.portCode ?? ''}</span></>}</span>
                      <span title={`${p.reason}${p.actorCode ? ` · ${p.actorCode}` : ''}`}
                        style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {p.reason}</span>
                      <span>
                        <button type="button" disabled={!mayChange || busy} style={ghost}
                          onClick={() => forget(p)}>삭제</button>
                      </span>
                    </Row>
                  ))}
                </Grid>
              )}
            <p className="hint" style={{ padding: '8px 12px 10px', margin: 0 }}>
              "셀 미지정"은 포트에서 설비가 받아 간 것까지만 확인된 상태입니다. 제어기는 설비 안을 보지
              못하므로 어느 칸에 들어갔는지는 알 수 없습니다. 위치를 모르는 것과는 다른 상태입니다.
            </p>
          </Panel>

          <Panel id="inv-transit" title="반송 중인 화물" aux={`${inTransport.length}개`}>
            {inTransport.length === 0
              ? <Empty title="반송 중인 화물이 없습니다" />
              : (
                <Grid cols="1fr 96px 86px" head={['화물', '작업 단계', '위치']}>
                  {inTransport.map(c => (
                    <Row key={c.code}>
                      <span className="mono">{c.code}</span>
                      <span className="mono" style={{ fontSize: 10.5 }}>{c.status}</span>
                      <span><Chip tone={c.where === 'UNKNOWN' ? 'er' : 'ac'}>{WHERE[c.where] ?? c.where}</Chip></span>
                    </Row>
                  ))}
                </Grid>
              )}
            <p className="hint" style={{ padding: '8px 12px 10px', margin: 0 }}>
              작업이 가진 화물의 위치는 그 작업이 정합니다. 여기에 셀을 기록하려 하면 거부됩니다 — 한 화물에
              대한 답이 둘이 되는 것이, 하나가 덜 자세한 것보다 나쁩니다.
            </p>
          </Panel>

          <Panel id="inv-ports" title="포트 인계 기록" aux={`${storagePorts.length}곳`}>
            {storagePorts.length === 0
              ? <Empty title="설비에 연결된 포트가 없습니다" />
              : (
                <div style={{ display: 'grid', gap: 5, padding: '8px 12px 12px' }}>
                  {storagePorts.map(p => (
                    <button key={p.id} type="button" disabled={!mayChange || busy} style={ghostWide}
                      onClick={() => atPort(p.id, p.equipmentName || p.equipmentCode || p.code)}>
                      {p.equipmentName || p.equipmentCode || p.code} <span className="mono" style={{ opacity: .6 }}>· {p.code}</span>
                    </button>
                  ))}
                  <p className="hint" style={{ margin: 0 }}>
                    셀을 모르는 채 설비가 받아 간 것만 기록합니다. 나중에 확인되면 셀로 옮겨 적으면 됩니다.
                  </p>
                </div>
              )}
          </Panel>
        </div>

        <section className="card">
          <div className="ch">
            <h2>랙</h2>
            <span className="aux">점유와 사용 금지는 다른 사실입니다. 한 칸에 둘 다 표시될 수 있습니다</span>
          </div>
          <div className="cb" style={{ display: 'grid', gap: 10 }}>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
              <select style={wide} value={rackId ?? ''} disabled={places.length === 0}
                onChange={e => setRackId(e.target.value ? Number(e.target.value) : null)}>
                {places.map(p => (
                  <option key={p.rackId} value={p.rackId}>
                    {(p.unitName || p.unitCode)} · {p.rackCode} — {p.occupied}/{p.cells}칸{p.enabled ? '' : ' (중지)'}
                  </option>
                ))}
              </select>
              <input style={field} value={carrier} placeholder="화물 ID" aria-label="화물 ID"
                onChange={e => setCarrier(e.target.value)} />
              <span className="hint" style={{ margin: 0 }}>
                {mayChange
                  ? '화물 ID를 넣고 칸을 누르면 그 칸에 기록합니다. 확인 방법을 묻습니다.'
                  : 'Lv3 운영 관리 권한이 있어야 기록할 수 있습니다.'}
              </span>
            </div>
            {places.length === 0
              ? <Empty title="보관 장소가 없습니다"
                  hint="랙이 있는 설비가 보관 장소입니다. 설비 화면에서 랙을 먼저 구성하세요." />
              : cells.length === 0
                ? <Empty title="셀이 없습니다" hint="이 랙의 크기에서 생성된 셀이 아직 없습니다." />
                : <CellGrid cells={cells} columns={place?.columns ?? 1}
                    onPick={mayChange && !busy ? intoCell : undefined} />}
            <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', fontSize: 11, color: 'var(--mut)' }}>
              <Legend swatch={{ background: '#E3EDF9', borderColor: 'var(--oht)' }} label="화물 있음" />
              <Legend swatch={{ background: 'var(--srf)', borderColor: 'var(--ln2)' }} label="빈 칸" />
              <Legend swatch={{ background: '#FBF0DA', borderColor: 'var(--warn)' }} label="사용 금지" />
              <Legend swatch={{ background: '#E3EDF9', borderColor: 'var(--warn)' }} label="사용 금지인데 화물 있음" />
            </div>
            <p className="hint" style={{ margin: 0 }}>
              {place ? `${place.unitName || place.unitCode} · ${place.rackCode} · ${place.levels}단 × ${place.columns}열 · 사용 금지 ${place.blockedCells}칸. ` : ''}
              사용 금지는 비워 두기로 한 결정이고, 화물 있음은 지금 들어 있다는 사실입니다. 둘이 한 칸에 겹칠
              수 있어 따로 표시합니다 — 하나로 합치면 일부러 비워 둔 칸이 그냥 찬 칸으로 보이고, 왜 비워
              뒀는지가 사라집니다.
            </p>
          </div>
        </section>
      </div>
    </>
  );
}

/** The rack as it stands: level 1 at the bottom, so the picture matches the shelf. */
function CellGrid({ cells, columns, onPick }:
  { cells: CellView[]; columns: number; onPick?: (c: CellView) => void }) {
  const levels = [...new Set(cells.map(c => c.level))].sort((a, b) => b - a);
  return (
    <div style={{ display: 'grid', gap: 7 }}>
      {levels.map(level => (
        <div key={level} style={{ display: 'flex', gap: 5, alignItems: 'center' }}>
          <span className="mono" style={{ fontSize: 10, color: 'var(--mut)', width: 26 }}>{level}단</span>
          <div style={{ display: 'grid', gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`, gap: 4, flex: 1 }}>
            {cells.filter(c => c.level === level).sort((a, b) => a.col - b.col).map(c => (
              <button key={c.slotId} type="button" disabled={!onPick} onClick={() => onPick?.(c)}
                title={describe(c)}
                style={{
                  // Two marks, two channels: what is in it fills the cell, the decision about it
                  // draws the border. Neither can hide the other.
                  border: `1px solid ${!c.usable ? 'var(--warn)' : c.carrierCode ? 'var(--oht)' : 'var(--ln2)'}`,
                  background: c.carrierCode ? '#E3EDF9' : c.usable ? 'var(--srf)' : '#FBF0DA',
                  color: c.carrierCode ? '#13395E' : c.usable ? 'var(--ink2)' : '#8A5C05',
                  borderRadius: 4, padding: '6px 2px', fontSize: 9.5, fontFamily: 'var(--mono)',
                  cursor: onPick ? 'pointer' : 'default', overflow: 'hidden', whiteSpace: 'nowrap',
                  textOverflow: 'ellipsis', fontWeight: c.carrierCode ? 600 : 400,
                }}>
                {c.carrierCode ?? c.col}
              </button>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

function Legend({ swatch, label }: { swatch: React.CSSProperties; label: string }) {
  return (
    <span style={{ display: 'inline-flex', gap: 5, alignItems: 'center' }}>
      <i style={{ width: 13, height: 13, borderRadius: 3, border: '1px solid', display: 'inline-block', ...swatch }} />
      {label}
    </span>
  );
}

/** Where it is, named the way somebody standing on the floor would say it. */
function placeOf(p: Placement): string {
  const unit = p.unitName || p.unitCode || '';
  if (p.slotCode) return [unit, p.rackCode, p.slotCode].filter(Boolean).join(' · ');
  return `${unit || p.portCode || '포트'} 인계 · 어느 칸인지는 기록되지 않았습니다`;
}

/**
 * Both facts in one sentence, in the order somebody would ask them, after the cell's place spelled
 * out in words. The code says the same thing, but spelling it out once is what teaches somebody
 * which half of `20/08` is the column.
 */
function describe(c: CellView): string {
  const held = c.carrierCode ? `${c.carrierCode} 있음` : '비어 있음';
  const blocked = c.usable ? '' : ` · 사용 금지 — ${c.unusableReason ?? '사유 없음'}`;
  return `${c.code} · ${c.col}열 ${c.level}단 · ${held}${blocked}`;
}

const TERMINAL = new Set(['COMPLETED', 'CANCELLED', 'FAILED']);
const WHERE: Record<string, string> = {
  SOURCE: '출발지', VEHICLE: '차량', DESTINATION: '목적지', UNKNOWN: '위치 모름',
};

function explain(code: string): string {
  if (code === 'CARRIER_IN_TRANSPORT') return '진행 중인 작업이 이 화물을 가지고 있습니다. 그 작업이 위치를 정하므로 여기서는 기록하지 않습니다.';
  if (code === 'CELL_OCCUPIED') return '그 칸에는 이미 다른 화물이 있습니다. 같은 순간에 다른 사람이 먼저 기록했을 수 있습니다.';
  if (code === 'CELL_NOT_USABLE') return '사용 금지로 표시된 칸입니다. 비워 두기로 한 결정이 있으므로 채우지 않습니다. 필요하면 설비 화면에서 금지를 먼저 해제하세요.';
  if (code === 'CELL_NOT_FOUND') return '그 칸을 찾지 못했습니다. 랙을 다시 불러와 주세요.';
  if (code === 'RACK_DISABLED') return '사용 중지된 랙이나 설비입니다.';
  if (code === 'PLACEMENT_REASON_REQUIRED') return '어떻게 확인했는지 적어야 기록합니다. 근거 없는 위치 기록은 추측과 구분되지 않습니다.';
  if (code === 'CARRIER_CODE_REQUIRED') return '화물 ID가 필요합니다.';
  if (code === 'PLACEMENT_NOT_FOUND') return '그 화물의 위치 기록이 없습니다.';
  if (code === 'PORT_NOT_FOUND') return '그 포트를 찾지 못했습니다.';
  if (code === 'ROLE_MANAGER_REQUIRED') return 'Lv3 운영 관리 이상 권한이 필요합니다.';
  return code;
}

const field: React.CSSProperties = {
  border: '1px solid var(--ln2)', borderRadius: 5, padding: '5px 8px',
  background: 'var(--srf)', color: 'var(--ink)', fontSize: 12, minWidth: 130,
};
const wide: React.CSSProperties = { ...field, minWidth: 260 };
const ghost: React.CSSProperties = {
  border: '1px solid var(--ln2)', background: 'var(--srf)', color: 'var(--ink2)',
  borderRadius: 4, padding: '2px 8px', fontSize: 10.5, cursor: 'pointer',
};
const ghostWide: React.CSSProperties = { ...ghost, padding: '5px 10px', fontSize: 11.5, textAlign: 'left' };
