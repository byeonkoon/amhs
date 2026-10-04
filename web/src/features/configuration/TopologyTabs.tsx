import React from 'react';
import {
  ApiError, get, opsApi, send,
  type Cfg, type EdgeSettings, type NodeSettings, type Session,
} from '../../api';
import { Chip, Empty } from '../../shell/States';
import { Grid, Row } from '../../shell/Panel';

/**
 * The track's own parameters.
 *
 * <p>These sit with the resources because that is where an engineer looks for them, but changing
 * them is administrator work: a mistake here reaches every vehicle that routes through the place,
 * and it does not announce itself until something cannot get where it was told to go. Anyone may
 * read; the controls are open only to a session that may change topology, and the server refuses
 * regardless of what the screen offers.
 */
export function NodeTab({ session, revisionId }: { session: Session; revisionId: number }) {
  return <TrackTab session={session} revisionId={revisionId} kind="node" />;
}
export function SegmentTab({ session, revisionId }: { session: Session; revisionId: number }) {
  return <TrackTab session={session} revisionId={revisionId} kind="segment" />;
}

type Kind = 'node' | 'segment';

function TrackTab({ session, revisionId, kind }: { session: Session; revisionId: number; kind: Kind }) {
  const [nodes, setNodes] = React.useState<Cfg<NodeSettings>[]>([]);
  const [segments, setSegments] = React.useState<Cfg<EdgeSettings>[]>([]);
  const [notice, setNotice] = React.useState<{ tone: string; text: string } | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [reload, setReload] = React.useState(0);
  const mayEdit = session.allows.includes('topology');

  React.useEffect(() => {
    const c = new AbortController();
    // An aborted request is this effect being replaced, not an empty answer.
    const ignoreCancel = <T,>(set: (v: T[]) => void) => (cause: unknown) => {
      if ((cause as Error)?.name !== 'AbortError') set([]);
    };
    get<Cfg<NodeSettings>[]>(opsApi.nodes(revisionId), c.signal).then(setNodes).catch(ignoreCancel(setNodes));
    if (kind === 'segment')
      get<Cfg<EdgeSettings>[]>(opsApi.segments(revisionId), c.signal).then(setSegments).catch(ignoreCancel(setSegments));
    return () => c.abort();
  }, [revisionId, kind, reload]);

  const nodeCode = (id: number) => nodes.find(n => n.id === id)?.settings.code ?? String(id);

  async function change(url: string, version: number, body: unknown, what: string) {
    setBusy(true); setNotice(null);
    try {
      await send(`${url}?expectedVersion=${version}`, 'PUT', body, what);
      setReload(v => v + 1);
      setNotice({ tone: 'ok', text: '변경했습니다.' });
    } catch (cause) {
      setNotice({ tone: 'er', text: cause instanceof ApiError ? explain(cause.message) : '변경하지 못했습니다.' });
    } finally { setBusy(false); }
  }
  async function act(url: string, method: 'POST' | 'DELETE', body: unknown, what: string, done: string) {
    setBusy(true); setNotice(null);
    try {
      await send(url, method, body, what);
      setReload(v => v + 1);
      setNotice({ tone: 'ok', text: done });
    } catch (cause) {
      setNotice({ tone: 'er', text: cause instanceof ApiError ? explain(cause.message) : '처리하지 못했습니다.' });
    } finally { setBusy(false); }
  }
  const removeStyle: React.CSSProperties = {
    border: '1px solid var(--crit)', background: 'var(--srf)', color: 'var(--crit)',
    borderRadius: 5, padding: '2px 10px', fontSize: 12, cursor: 'pointer',
  };

  const banner = (
    <>
      {notice && <p style={{ margin: '0 0 9px', fontSize: 12 }}>
        <Chip tone={notice.tone}>{notice.tone === 'er' ? '거부' : '완료'}</Chip> {notice.text}
      </p>}
      {!mayEdit && <p className="hint" style={{ margin: '0 0 9px' }}>
        조회만 가능합니다. 선로는 모든 차량에 걸리므로 관리자 권한을 요구합니다.
      </p>}
    </>
  );

  if (kind === 'node') return (
    <div>
      {banner}
      {nodes.length === 0 ? <Empty title="이 지도 revision에 노드가 없습니다" /> : (
        <Grid cols="80px 1fr 90px 90px 96px 96px 96px 96px 104px 104px 74px"
          head={['코드', '', 'X (mm)', 'Y (mm)', '사용', '정지 노드', '합류 지점', '충전 지점', '대기(AGV)', '대기(OHT)', '']}>
          {nodes.map(n => (
            <Row key={n.id}>
              <span className="mono">{n.settings.code}</span>
              <span className="muted mono">#{n.id}</span>
              <span className="mono">{n.settings.xMm.toLocaleString()}</span>
              <span className="mono">{n.settings.yMm.toLocaleString()}</span>
              <span>
                <button type="button" disabled={!mayEdit || busy} style={toggleStyle(n.settings.enabled)}
                  onClick={() => change(`${opsApi.nodes(revisionId)}/${n.id}`, n.version,
                    { ...n.settings, enabled: !n.settings.enabled }, '노드 사용 여부 변경')}>
                  {n.settings.enabled ? '사용' : '사용 금지'}
                </button>
              </span>
              <span>
                <button type="button" disabled={!mayEdit || busy} style={flagStyle(n.settings.stopNode)}
                  onClick={() => change(`${opsApi.nodes(revisionId)}/${n.id}`, n.version,
                    { ...n.settings, stopNode: !n.settings.stopNode }, '정지 노드 지정 변경')}>
                  {n.settings.stopNode ? '정지' : '—'}
                </button>
              </span>
              <span>
                <button type="button" disabled={!mayEdit || busy} style={flagStyle(n.settings.mergePoint)}
                  onClick={() => change(`${opsApi.nodes(revisionId)}/${n.id}`, n.version,
                    { ...n.settings, mergePoint: !n.settings.mergePoint }, '합류 지점 지정 변경')}>
                  {n.settings.mergePoint ? '합류' : '—'}
                </button>
              </span>
              <span>
                <button type="button" disabled={!mayEdit || busy} style={flagStyle(!!n.settings.chargePoint)}
                  onClick={() => change(`${opsApi.nodes(revisionId)}/${n.id}`, n.version,
                    // Turning a node into a charger gives up its waiting place: a vehicle standing
                    // there to wait is a charger nobody else can use, and the server refuses both.
                    { ...n.settings, chargePoint: !n.settings.chargePoint, waitFor: n.settings.chargePoint ? n.settings.waitFor : null },
                    '충전 지점 지정 변경')}>
                  {n.settings.chargePoint ? '충전' : '—'}
                </button>
              </span>
              {/* A waiting place belongs to one kind of vehicle: the two kinds do not share track,
                  so marking one clears the other rather than adding to it. */}
              {(['AGV', 'OHT'] as const).map(kind => (
                <span key={kind}>
                  <button type="button" disabled={!mayEdit || busy || !!n.settings.chargePoint}
                    style={flagStyle(n.settings.waitFor === kind)}
                    title={n.settings.chargePoint ? '충전 지점은 대기 위치로 쓰지 않습니다.' : undefined}
                    onClick={() => change(`${opsApi.nodes(revisionId)}/${n.id}`, n.version,
                      { ...n.settings, waitFor: n.settings.waitFor === kind ? null : kind }, `대기 위치(${kind}) 지정 변경`)}>
                    {n.settings.waitFor === kind ? '대기' : '—'}
                  </button>
                </span>
              ))}
              <span>
                {/* Removing is not undone by changing it back, so the server names whatever still
                    points at the node and refuses; this only asks. */}
                <button type="button" disabled={!mayEdit || busy} style={removeStyle}
                  onClick={() => {
                    if (!window.confirm(`노드 ${n.settings.code}을 지웁니다. 이 노드를 쓰는 구간·포트·차량·Zone이 있으면 거절됩니다.`)) return;
                    void act(`${opsApi.nodes(revisionId)}/${n.id}`, 'DELETE', undefined,
                      `노드 ${n.settings.code} 삭제`, '지웠습니다.');
                  }}>삭제</button>
              </span>
            </Row>
          ))}
        </Grid>
      )}
      {mayEdit && <NewNode busy={busy} onCreate={draft =>
        act(opsApi.nodes(revisionId), 'POST', draft, `노드 ${draft.code} 추가`, '추가했습니다.')} />}
      <p className="hint" style={{ marginTop: 9 }}>
        차량이 점유 중인 노드는 사용 금지로 바꿀 수 없습니다. 그 자리에 있어도 된다고 허가받은 차량이 있는데
        닫으면, 다른 차량을 그리로 보내게 됩니다. 정지 노드에서는 이동 허가가 끝나고 연장 없이 차량이 섭니다.
        합류 지점에서는 먼저 요청한 차량이 먼저 지나갑니다. 충전 지점은 AGV가 충전하는 자리입니다.
        대기 위치는 할 일 없는 차량이 서 있을 자리이며, 차종을 함께 정합니다 — OHT와 AGV는 선로를 공유하지
        않으므로 한쪽의 대기 자리가 다른 쪽에는 갈 수 없는 곳입니다. 차량에 대기 노드가 따로 지정돼 있으면
        그 노드가 먼저이고, 없으면 같은 차종의 대기 위치 중 가까운 빈 자리로 갑니다. 복귀까지의 대기 시간은
        시스템 옵션의 Home 복귀 시간입니다(0이면 보내지 않습니다).
      </p>
    </div>
  );

  return (
    <div>
      {banner}
      {segments.length === 0 ? <Empty title="이 지도 revision에 구간이 없습니다" /> : (
        <Grid cols="54px 1fr 108px 116px 96px 74px" head={['', '구간', '비용(초)', '곡률(mm)', '사용', '']}>
          {segments.map(e => (
            <Row key={e.id}>
              <span className="muted mono">#{e.id}</span>
              <span className="mono">{nodeCode(e.settings.fromNodeId)} → {nodeCode(e.settings.toNodeId)}</span>
              <span>
                <CostField value={e.settings.cost} disabled={!mayEdit || busy}
                  onCommit={cost => change(`${opsApi.segments(revisionId)}/${e.id}`, e.version,
                    { ...e.settings, cost }, '구간 비용 변경')} />
              </span>
              <span title="구간 가운데가 직선에서 벗어나는 거리. +는 시작→끝 방향의 왼쪽(평면도 기준), −는 오른쪽, 0은 직선.">
                <CostField value={e.settings.curveMm ?? 0} disabled={!mayEdit || busy}
                  onCommit={curveMm => change(`${opsApi.segments(revisionId)}/${e.id}`, e.version,
                    { ...e.settings, curveMm }, '구간 곡률 변경')} />
              </span>
              <span>
                <button type="button" disabled={!mayEdit || busy} style={toggleStyle(e.settings.enabled)}
                  onClick={() => change(`${opsApi.segments(revisionId)}/${e.id}`, e.version,
                    { ...e.settings, enabled: !e.settings.enabled }, '구간 사용 여부 변경')}>
                  {e.settings.enabled ? '사용' : '통행 금지'}
                </button>
              </span>
              <span>
                <button type="button" disabled={!mayEdit || busy} style={removeStyle}
                  onClick={() => {
                    if (!window.confirm(`구간 ${nodeCode(e.settings.fromNodeId)} → ${nodeCode(e.settings.toNodeId)}을 지웁니다.`)) return;
                    void act(`${opsApi.segments(revisionId)}/${e.id}`, 'DELETE', undefined,
                      '구간 삭제', '지웠습니다.');
                  }}>삭제</button>
              </span>
            </Row>
          ))}
        </Grid>
      )}
      {mayEdit && <NewSegment nodes={nodes} busy={busy} onCreate={draft =>
        act(opsApi.segments(revisionId), 'POST', draft, '구간 추가', '추가했습니다.')} />}
      <p className="hint" style={{ marginTop: 9 }}>
        구간의 양 끝은 그 구간이 무엇인지를 정하므로 여기서 바꾸지 않습니다. 끝을 바꾸는 것은 고치는 것이 아니라
        다른 구간을 만드는 것입니다. 양 끝 중 하나를 차량이 점유 중이면 통행 금지로 바꿀 수 없습니다.
        구간은 한 방향입니다 — 양방향으로 다니게 하려면 반대 방향도 따로 만듭니다. 곡률은 Bay 진입처럼
        차량이 직각으로 돌 수 없는 자리를 표현합니다.
      </p>
    </div>
  );
}

/**
 * A new node: a code and where it is.
 *
 * <p>Nothing else is asked for. A node is a place first; whether it is a stop, a junction, a
 * charger or a waiting place is decided by looking at the layout, which is easier once the node is
 * on it. The flags are one click away in the row.
 */
function NewNode({ busy, onCreate }: { busy: boolean; onCreate: (draft: NodeSettings) => void }) {
  const [code, setCode] = React.useState('');
  const [x, setX] = React.useState('0');
  const [y, setY] = React.useState('0');
  const ready = code.trim().length > 0 && Number.isFinite(Number(x)) && Number.isFinite(Number(y));
  return (
    <div style={{ display: 'flex', gap: 7, alignItems: 'center', marginTop: 10, flexWrap: 'wrap' }}>
      <span className="hint">노드 추가</span>
      <input value={code} onChange={e => setCode(e.target.value)} placeholder="코드" style={fieldStyle(96)} />
      <input type="number" value={x} onChange={e => setX(e.target.value)} placeholder="X (mm)" style={fieldStyle(96)} />
      <input type="number" value={y} onChange={e => setY(e.target.value)} placeholder="Y (mm)" style={fieldStyle(96)} />
      <button type="button" disabled={!ready || busy} style={addStyle(ready && !busy)}
        onClick={() => {
          onCreate({ code: code.trim(), xMm: Number(x), yMm: Number(y), enabled: true, stopNode: false, mergePoint: false });
          setCode('');
        }}>추가</button>
      <span className="hint">새 노드는 어떤 구간도 닿지 않은 상태로 생깁니다 — 선로를 그리는 중이라는 뜻이고, 구간을 붙이면 이어집니다.</span>
    </div>
  );
}

/** A new one-way segment between two nodes of this map. */
function NewSegment({ nodes, busy, onCreate }: {
  nodes: Cfg<NodeSettings>[]; busy: boolean; onCreate: (draft: EdgeSettings) => void;
}) {
  const [from, setFrom] = React.useState('');
  const [to, setTo] = React.useState('');
  const [cost, setCost] = React.useState('3000');
  const [curve, setCurve] = React.useState('0');
  const ready = from !== '' && to !== '' && from !== to && Number(cost) >= 0;
  const options = [...nodes].sort((a, b) => a.settings.code.localeCompare(b.settings.code));
  return (
    <div style={{ display: 'flex', gap: 7, alignItems: 'center', marginTop: 10, flexWrap: 'wrap' }}>
      <span className="hint">구간 추가</span>
      <select value={from} onChange={e => setFrom(e.target.value)} style={fieldStyle(130)}>
        <option value="">시작 노드</option>
        {options.map(n => <option key={n.id} value={n.id}>{n.settings.code}</option>)}
      </select>
      <span>→</span>
      <select value={to} onChange={e => setTo(e.target.value)} style={fieldStyle(130)}>
        <option value="">끝 노드</option>
        {options.map(n => <option key={n.id} value={n.id}>{n.settings.code}</option>)}
      </select>
      <input type="number" value={cost} onChange={e => setCost(e.target.value)} title="비용" style={fieldStyle(84)} />
      <input type="number" value={curve} onChange={e => setCurve(e.target.value)} title="곡률(mm)" style={fieldStyle(84)} />
      <button type="button" disabled={!ready || busy} style={addStyle(ready && !busy)}
        onClick={() => {
          onCreate({ fromNodeId: Number(from), toNodeId: Number(to), cost: Number(cost), enabled: true, curveMm: Number(curve) });
          setFrom(''); setTo('');
        }}>추가</button>
    </div>
  );
}

function fieldStyle(width: number): React.CSSProperties {
  return {
    border: '1px solid var(--ln2)', borderRadius: 5, padding: '3px 6px', fontSize: 12, width,
    background: 'var(--srf)', color: 'var(--ink)',
  };
}
function addStyle(ready: boolean): React.CSSProperties {
  return {
    border: '1px solid var(--acc)', background: ready ? 'var(--acc)' : 'var(--srf)',
    color: ready ? '#fff' : 'var(--mut)', borderRadius: 5, padding: '3px 14px', fontSize: 12,
    cursor: ready ? 'pointer' : 'default',
  };
}

/** Cost is typed and committed, not changed per keystroke: each commit is a versioned write. */
function CostField({ value, disabled, onCommit }: { value: number; disabled: boolean; onCommit: (v: number) => void }) {
  const [draft, setDraft] = React.useState(String(value));
  React.useEffect(() => setDraft(String(value)), [value]);
  const dirty = draft !== String(value);
  return (
    <span style={{ display: 'flex', gap: 4 }}>
      <input type="number" value={draft} disabled={disabled} onChange={e => setDraft(e.target.value)}
        style={{
          border: '1px solid var(--ln2)', borderRadius: 4, padding: '2px 5px', fontSize: 11,
          width: 62, fontFamily: 'var(--mono)', background: 'var(--srf)', color: 'var(--ink)',
        }} />
      {dirty && !disabled && (
        <button type="button" onClick={() => onCommit(Number(draft))}
          style={{ border: '1px solid var(--acc)', background: 'var(--acc)', color: '#fff', borderRadius: 4, padding: '1px 7px', fontSize: 10, cursor: 'pointer' }}>
          적용
        </button>
      )}
    </span>
  );
}

function toggleStyle(on: boolean): React.CSSProperties {
  return {
    border: `1px solid ${on ? 'var(--ln2)' : 'var(--warn)'}`,
    background: on ? 'var(--srf)' : '#FBF0DA',
    color: on ? 'var(--ink2)' : '#8A5C05',
    borderRadius: 4, padding: '2px 8px', fontSize: 10.5, cursor: 'pointer', width: '100%',
  };
}

function flagStyle(on: boolean): React.CSSProperties {
  return {
    border: `1px solid ${on ? 'var(--acc)' : 'var(--ln2)'}`,
    background: on ? 'var(--acc)' : 'var(--srf)',
    color: on ? '#fff' : 'var(--mut)',
    borderRadius: 4, padding: '2px 8px', fontSize: 10.5, cursor: 'pointer', width: '100%',
  };
}

function explain(code: string): string {
  if (code === 'NODE_IN_USE') return '차량이 점유 중인 노드입니다. 점유가 풀린 뒤에 바꿀 수 있습니다.';
  if (code === 'SEGMENT_IN_USE') return '양 끝 중 한 곳을 차량이 점유 중입니다. 점유가 풀린 뒤에 바꿀 수 있습니다.';
  if (code === 'EDGE_ENDPOINTS_FIXED') return '구간의 양 끝은 여기서 바꾸지 않습니다.';
  if (code === 'NODE_CODE_IN_USE') return '같은 revision에 같은 코드의 노드가 이미 있습니다.';
  if (code === 'VERSION_CONFLICT') return '다른 곳에서 먼저 수정되었습니다. 새로 불러와 비교해 주세요.';
  if (code === 'SITE_NOT_IN_MAINTENANCE') return '유지보수 상태에서만 선로를 바꿀 수 있습니다.';
  if (code === 'ROLE_ADMIN_REQUIRED') return '선로 변경은 관리자 권한이 필요합니다.';
  return code;
}
