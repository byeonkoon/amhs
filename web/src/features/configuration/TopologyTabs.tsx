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
        <Grid cols="80px 1fr 90px 90px 96px 96px 96px" head={['코드', '', 'X (mm)', 'Y (mm)', '사용', '정지 노드', '합류 지점']}>
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
            </Row>
          ))}
        </Grid>
      )}
      <p className="hint" style={{ marginTop: 9 }}>
        차량이 점유 중인 노드는 사용 금지로 바꿀 수 없습니다. 그 자리에 있어도 된다고 허가받은 차량이 있는데
        닫으면, 다른 차량을 그리로 보내게 됩니다. 정지 노드에서는 이동 허가가 끝나고 연장 없이 차량이 섭니다.
        합류 지점에서는 먼저 요청한 차량이 먼저 지나갑니다.
      </p>
    </div>
  );

  return (
    <div>
      {banner}
      {segments.length === 0 ? <Empty title="이 지도 revision에 구간이 없습니다" /> : (
        <Grid cols="54px 1fr 108px 96px" head={['', '구간', '비용(초)', '사용']}>
          {segments.map(e => (
            <Row key={e.id}>
              <span className="muted mono">#{e.id}</span>
              <span className="mono">{nodeCode(e.settings.fromNodeId)} → {nodeCode(e.settings.toNodeId)}</span>
              <span>
                <CostField value={e.settings.cost} disabled={!mayEdit || busy}
                  onCommit={cost => change(`${opsApi.segments(revisionId)}/${e.id}`, e.version,
                    { ...e.settings, cost }, '구간 비용 변경')} />
              </span>
              <span>
                <button type="button" disabled={!mayEdit || busy} style={toggleStyle(e.settings.enabled)}
                  onClick={() => change(`${opsApi.segments(revisionId)}/${e.id}`, e.version,
                    { ...e.settings, enabled: !e.settings.enabled }, '구간 사용 여부 변경')}>
                  {e.settings.enabled ? '사용' : '통행 금지'}
                </button>
              </span>
            </Row>
          ))}
        </Grid>
      )}
      <p className="hint" style={{ marginTop: 9 }}>
        구간의 양 끝은 그 구간이 무엇인지를 정하므로 여기서 바꾸지 않습니다. 끝을 바꾸는 것은 고치는 것이 아니라
        다른 구간을 만드는 것입니다. 양 끝 중 하나를 차량이 점유 중이면 통행 금지로 바꿀 수 없습니다.
      </p>
    </div>
  );
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
