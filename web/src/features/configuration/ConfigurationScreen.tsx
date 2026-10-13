import React from 'react';
import {
  ApiError, cfgApi, equipmentApi, get, getAs, opsApi, send, sendText, ROLE_LABEL,
  type Cfg, type ConnectionSettings, type EquipmentCommunicationSettings, type EquipmentSettings,
  type InstalledMapping, type MapData, type MapRevision,
  type OperationOptions, type PortSettings, type ProtocolSettings,
  type Session, type VdaProfileSettings, type VehicleSettings,
} from '../../api';
import { Chip, Empty, Failed, Loading } from '../../shell/States';
import { Grid, Row } from '../../shell/Panel';
import { NodeTab, SegmentTab } from './TopologyTabs';
import { layerOfNode } from '../map/MapView';
import {
  BCR_POLICY, CONTROL_AUTHORITY, chargeNodeRequired, chargingPolicyOptions,
  connectionIssues, equipmentLinkIssues, explainCode, protocolIssues, PROTO_TYPES, roleOptions, vdaProfileIssues,
  vehicleIssues, vehicleVdaIssues, versionOptions,
} from './rules';

/**
 * Resources: what the controller commands. Site-wide options are not here — they apply to every
 * vehicle and every command rather than to one of these, so they sit behind the account menu
 * where changing them needs the authority that reach deserves.
 */
type Tab = "revisions" | "vehicles" | "vda" | "ports" | "nodes" | "segments" | "connections" | "protocols" | "mappings" | "equipmentLinks";
const TABS: { key: Tab; label: string }[] = [
  { key: 'revisions', label: '지도 버전' },
  { key: 'vehicles', label: '차량' }, { key: 'vda', label: 'VDA 프로파일' },
  { key: 'ports', label: '포트' },
  { key: 'nodes', label: '노드' }, { key: 'segments', label: '구간' },
  { key: 'connections', label: '연결' }, { key: 'protocols', label: '프로토콜' },
  { key: 'mappings', label: '매핑 문서' },
  { key: 'equipmentLinks', label: '설비 통신' },
];

export function ConfigurationScreen({ map, session, revisions, onChanged }: {
  map: MapData; session: Session; revisions: MapRevision[]; onChanged: () => void;
}) {
  const [tab, setTab] = React.useState<Tab>('vehicles');
  // What may be saved follows the login, not a value typed into this screen. One place decides it,
  // so a screen cannot offer a save the server will refuse.
  const canWrite = session.allows.includes('resources');
  return (
    <>
      <div className="statbar">
        <span className="s"><span className="k">계정</span><span className="v mono">{session.code}</span></span>
        <span className="s"><span className="k">권한</span><span className="v">{ROLE_LABEL[session.role]}</span></span>
        <span className="s"><span className="k">저장</span>
          <span className="v">{canWrite ? '가능' : '불가'}</span>
          <span className="d">{canWrite ? '' : 'Lv4 관리자 권한이 필요합니다'}</span></span>
      </div>
      <div className="card">
        <div className="ch" style={{ gap: 4 }}>
          {TABS.map(t => (
            <button key={t.key} type="button" onClick={() => setTab(t.key)} aria-pressed={tab === t.key}
              style={{
                border: '1px solid var(--ln2)', borderRadius: 5, padding: '5px 13px', fontSize: 12, cursor: 'pointer',
                background: tab === t.key ? 'var(--acc)' : 'var(--srf)', color: tab === t.key ? '#fff' : 'var(--ink2)',
              }}>{t.label}</button>
          ))}
          <span className="aux">저장은 Lv4 관리자 권한과 유지보수 상태가 필요합니다</span>
        </div>
        <div className="cb">
          {tab === 'revisions' && <RevisionTab revisions={revisions} canWrite={canWrite} onChanged={onChanged} />}
          {tab === 'vehicles' && <VehicleTab map={map} canWrite={canWrite} />}
          {tab === 'nodes' && <NodeTab session={session} revisionId={map.revisionId} />}
          {tab === 'segments' && <SegmentTab session={session} revisionId={map.revisionId} />}
          {tab === 'vda' && <VdaProfileTab canWrite={canWrite} />}
          {tab === 'ports' && <PortTab map={map} canWrite={canWrite} />}
          {tab === 'connections' && <ConnectionTab canWrite={canWrite} />}
          {tab === 'protocols' && <ProtocolTab canWrite={canWrite} />}
          {tab === 'mappings' && <MappingTab canWrite={canWrite} />}
          {tab === 'equipmentLinks' && <EquipmentLinkTab canWrite={canWrite} />}
        </div>
      </div>
    </>
  );
}

const btn: React.CSSProperties = { border: '1px solid var(--ln2)', background: 'var(--srf)', borderRadius: 5, padding: '5px 12px', fontSize: 12, cursor: 'pointer' };
const field: React.CSSProperties = { border: '1px solid var(--ln2)', borderRadius: 5, padding: '5px 8px', fontSize: 12, width: '100%' };
const row: React.CSSProperties = { display: 'grid', gridTemplateColumns: '140px 1fr', gap: 10, alignItems: 'center', marginBottom: 8 };

function useList<T>(url: string) {
  const [items, setItems] = React.useState<Cfg<T>[] | null>(null);
  const [error, setError] = React.useState<unknown>(null);
  const [tick, setTick] = React.useState(0);
  React.useEffect(() => {
    const c = new AbortController();
    setError(null); setItems(null);
    get<Cfg<T>[]>(url, c.signal).then(setItems).catch(e => { if ((e as Error).name !== 'AbortError') setError(e); });
    return () => c.abort();
  }, [url, tick]);
  return { items, error, reload: () => setTick(v => v + 1) };
}

/** Single-record settings such as the operation option. */
function useOne<T>(url: string) {
  const [item, setItem] = React.useState<Cfg<T> | null>(null);
  const [error, setError] = React.useState<unknown>(null);
  const [tick, setTick] = React.useState(0);
  React.useEffect(() => {
    const c = new AbortController();
    setError(null); setItem(null);
    get<Cfg<T>>(url, c.signal).then(setItem).catch(e => { if ((e as Error).name !== 'AbortError') setError(e); });
    return () => c.abort();
  }, [url, tick]);
  return { item, error, reload: () => setTick(v => v + 1) };
}

function SaveBar({ issues, canWrite, busy, message, onSave, saveLabel, onRemove }: {
  issues: string[]; canWrite: boolean; busy: boolean; message: string | null; onSave: () => void;
  saveLabel?: string; onRemove?: () => void;
}) {
  return (
    <div style={{ marginTop: 12, borderTop: '1px solid var(--ln)', paddingTop: 12 }}>
      {issues.length > 0 && (
        <ul style={{ margin: '0 0 10px', paddingLeft: 18, color: '#8E2B23', fontSize: 12 }}>
          {issues.map(i => <li key={i}>{i}</li>)}
        </ul>
      )}
      {message && <p style={{ margin: '0 0 10px', fontSize: 12 }}>{message}</p>}
      <button type="button" style={{ ...btn, background: 'var(--acc)', color: '#fff', borderColor: 'var(--acc)' }}
        disabled={!canWrite || issues.length > 0 || busy} onClick={onSave}>
        {busy ? '처리 중…' : (saveLabel ?? '저장')}
      </button>
      {/* Removing sits next to saving but is not the same act: it is the one that cannot be undone
          by filling the form in again, so it asks first and the server names what still depends. */}
      {onRemove && (
        <button type="button" style={{ ...btn, marginLeft: 8, borderColor: 'var(--crit)', color: 'var(--crit)' }}
          disabled={!canWrite || busy} onClick={onRemove}>삭제</button>
      )}
      {!canWrite && <span className="hint" style={{ marginLeft: 10 }}>Lv4 관리자로 로그인하면 저장할 수 있습니다.</span>}
    </div>
  );
}

/**
 * Which map the site runs on.
 *
 * <p>Every other tab edits the map in use; this one changes which map that is. It is the one change
 * that cannot be undone by editing it back -- orders, granted roads and reported positions are all
 * written against a revision -- so the server refuses it while any of that is open, and says which.
 */
function RevisionTab({ revisions, canWrite, onChanged }: {
  revisions: MapRevision[]; canWrite: boolean; onChanged: () => void;
}) {
  const [busy, setBusy] = React.useState(false);
  const [message, setMessage] = React.useState<{ tone: string; text: string } | null>(null);
  async function activate(r: MapRevision) {
    if (!window.confirm(`지도 버전 ${r.revision}을 사용하도록 바꿉니다. 차량 ${r.vehicles}대, 노드 ${r.nodes}개 구성입니다. 진행할까요?`)) return;
    setBusy(true); setMessage(null);
    try {
      await send(cfgApi.activateRevision(r.id), 'POST', undefined, `지도 버전 ${r.revision} 적용`);
      setMessage({ tone: 'ok', text: `지도 버전 ${r.revision}을 사용합니다. 이전 버전은 끝난 작업이 기록된 그대로 보관됩니다.` });
      onChanged();
    } catch (cause) {
      setMessage({ tone: 'er', text: cause instanceof ApiError ? explainCode(cause.message) : '바꾸지 못했습니다.' });
    } finally { setBusy(false); }
  }
  // A map laid out elsewhere comes in as a file. It becomes a draft of its own, so nothing the site
  // is running on changes until somebody chooses it above.
  async function upload(file: File) {
    setBusy(true); setMessage(null);
    try {
      const document = JSON.parse(await file.text());
      const made = await send<MapRevision>(cfgApi.importMap(), 'POST', document, `지도 파일 올리기 (${file.name})`);
      setMessage({
        tone: 'ok',
        text: `지도 버전 ${made.revision}을 만들었습니다 — 노드 ${made.nodes}개, 구간 ${made.segments}개, 포트 ${made.ports}개, 차량 ${made.vehicles}대. 아직 사용하지는 않습니다.`,
      });
      onChanged();
    } catch (cause) {
      setMessage({
        tone: 'er',
        text: cause instanceof ApiError ? explainCode(cause.message)
          : cause instanceof SyntaxError ? 'JSON 형식이 아닙니다. 파일 내용을 확인해 주세요.'
            : '올리지 못했습니다.',
      });
    } finally { setBusy(false); }
  }
  async function remove(r: MapRevision) {
    if (!window.confirm(`지도 버전 ${r.revision}과 그 안의 노드·구간·포트·차량을 지웁니다. 되돌릴 수 없습니다. 진행할까요?`)) return;
    setBusy(true); setMessage(null);
    try {
      await send(cfgApi.deleteRevision(r.id), 'DELETE', undefined, `지도 버전 ${r.revision} 삭제`);
      setMessage({ tone: 'ok', text: `지도 버전 ${r.revision}을 지웠습니다.` });
      onChanged();
    } catch (cause) {
      setMessage({ tone: 'er', text: cause instanceof ApiError ? explainCode(cause.message) : '지우지 못했습니다.' });
    } finally { setBusy(false); }
  }
  async function download(r: MapRevision) {
    setBusy(true); setMessage(null);
    try {
      // The export is behind the same login as the rest of configuration, so it goes out with the token.
      const document = await getAs<unknown>(cfgApi.exportMap(r.id));
      const url = URL.createObjectURL(new Blob([JSON.stringify(document, null, 2)], { type: 'application/json' }));
      const link = window.document.createElement('a');
      link.href = url; link.download = `amhs-map-r${r.revision}.json`;
      link.click();
      URL.revokeObjectURL(url);
      setMessage({ tone: 'ok', text: `지도 버전 ${r.revision}을 파일로 내려받았습니다. 고쳐서 다시 올리면 새 버전이 됩니다.` });
    } catch (cause) {
      setMessage({ tone: 'er', text: cause instanceof ApiError ? explainCode(cause.message) : '내보내지 못했습니다.' });
    } finally { setBusy(false); }
  }
  if (revisions.length === 0) return <Empty title="지도 버전을 읽지 못했습니다" />;
  return (
    <div>
      {message && <p style={{ margin: '0 0 9px', fontSize: 12 }}>
        <Chip tone={message.tone}>{message.tone === 'er' ? '거부' : '완료'}</Chip> {message.text}
      </p>}
      <Grid cols="90px 96px 1fr 1fr 1fr 1fr 190px"
        head={['버전', '상태', '노드', '구간', '포트', '차량', '']}>
        {revisions.map(r => (
          <Row key={r.id}>
            <span className="mono">{r.revision}</span>
            <span><Chip tone={r.status === 'ACTIVE' ? 'ok' : r.status === 'DRAFT' ? 'wr' : 'id'}>
              {r.status === 'ACTIVE' ? '사용 중' : r.status === 'DRAFT' ? '준비' : '보관'}
            </Chip></span>
            <span className="mono">{r.nodes}</span>
            <span className="mono">{r.segments}</span>
            <span className="mono">{r.ports}</span>
            <span className="mono">{r.vehicles}</span>
            <span style={{ display: 'flex', gap: 6 }}>
              {r.status === 'ACTIVE'
                ? <span className="hint">현재 사용</span>
                : <button type="button" disabled={!canWrite || busy} onClick={() => activate(r)}
                  style={{
                    border: '1px solid var(--acc)', background: canWrite ? 'var(--acc)' : 'var(--srf)',
                    color: canWrite ? '#fff' : 'var(--mut)', borderRadius: 5, padding: '3px 12px', fontSize: 12,
                    cursor: canWrite && !busy ? 'pointer' : 'default',
                  }}>사용</button>}
              <button type="button" disabled={busy} onClick={() => download(r)}
                title="이 버전을 JSON 파일로 받습니다. 고쳐서 다시 올리면 새 버전이 됩니다."
                style={{
                  border: '1px solid var(--ln2)', background: 'var(--srf)', color: 'var(--ink2)',
                  borderRadius: 5, padding: '3px 12px', fontSize: 12, cursor: busy ? 'default' : 'pointer',
                }}>내보내기</button>
              {r.status !== 'ACTIVE' && (
                <button type="button" disabled={!canWrite || busy} onClick={() => remove(r)}
                  title="쓰지 않고, 아무 기록도 가리키지 않는 버전만 지울 수 있습니다."
                  style={{
                    border: '1px solid var(--crit)', background: 'var(--srf)', color: 'var(--crit)',
                    borderRadius: 5, padding: '3px 12px', fontSize: 12,
                    cursor: canWrite && !busy ? 'pointer' : 'default',
                  }}>삭제</button>
              )}
            </span>
          </Row>
        ))}
      </Grid>
      <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginTop: 10, flexWrap: 'wrap' }}>
        <label htmlFor="map-file" style={{
          border: `1px solid ${canWrite ? 'var(--acc)' : 'var(--ln2)'}`,
          background: canWrite ? 'var(--acc)' : 'var(--srf)', color: canWrite ? '#fff' : 'var(--mut)',
          borderRadius: 5, padding: '5px 14px', fontSize: 12, cursor: canWrite && !busy ? 'pointer' : 'default',
        }}>{busy ? '처리 중…' : 'JSON 지도 파일 올리기'}</label>
        <input id="map-file" type="file" accept="application/json,.json,.txt" disabled={!canWrite || busy}
          style={{ display: 'none' }}
          onChange={e => {
            const file = e.target.files?.[0];
            e.target.value = '';   // the same file picked twice is two uploads, not one ignored
            if (file) void upload(file);
          }} />
        <span className="hint">
          nodes · segments · ports · vehicles · zones를 담은 한 파일입니다. 서로를 코드로 가리키며 id는 쓰지
          않습니다 — 파일 안의 id는 다른 DB에서 다른 것을 뜻하게 됩니다.
        </span>
      </div>
      <p className="hint" style={{ marginTop: 9 }}>
        지도 버전을 바꾸면 그 뒤의 모든 작업·경로·점유가 새 지도 위에서 결정됩니다. 그래서 진행 중인 작업,
        끝나지 않은 이동, 아직 반납되지 않은 구간 점유가 하나라도 있으면 거절합니다 — 그 기록들이 가리키는
        노드가 사라지기 때문입니다. 사이트를 유지보수 상태로 두고 바꾸며, 이전 버전은 지우지 않고 보관합니다.
        차량 코드는 사이트 전체에서 하나씩이므로, 버전마다 차량 이름이 다릅니다.
      </p>
    </div>
  );
}

function VehicleTab({ map, canWrite }: { map: MapData; canWrite: boolean }) {
  const { items, error, reload } = useList<VehicleSettings>(cfgApi.vehicles());
  // Read only: the site's communication type decides whether a vehicle needs a VDA profile.
  // Changing it belongs to the system options, not here.
  const { item: option } = useOne<OperationOptions>(cfgApi.system());
  const { items: profiles } = useList<VdaProfileSettings>(cfgApi.vdaProfiles());
  const commType = option?.settings.commType ?? null;
  const [draft, setDraft] = React.useState<Cfg<VehicleSettings> | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [message, setMessage] = React.useState<string | null>(null);
  React.useEffect(() => { setDraft(null); setMessage(null); }, [items]);
  if (error) return <Failed error={error} onRetry={reload} />;
  if (!items) return <Loading what="차량 설정" />;
  const s = draft?.settings;
  const set = (patch: Partial<VehicleSettings>) => setDraft(d => d && ({ ...d, settings: { ...d.settings, ...patch } }));
  // A draft with no id yet is one being added. The same form fills it, because a new vehicle needs
  // exactly the same answers as a changed one -- offering fewer is how a half-set-up vehicle happens.
  const adding = draft?.id === 0;
  const newVehicle = (): Cfg<VehicleSettings> => ({
    id: 0, version: 0,
    settings: {
      revisionId: map.revisionId, code: '', name: '', vType: 'OHT',
      widthMm: 1000, lengthMm: 1000, heightMm: 1000,
      initialNodeId: map.nodes[0]?.id ?? 0, homeNodeId: null, chargeNodeId: null,
      enabled: false, assignmentEnabled: false,
      controlAuthority: 'CONTROLLER_MANAGED', bcrPolicy: 'DISABLED', chargingPolicy: 'DISABLED',
      vdaProfileId: null, speedMmS: 2000,
    },
  });

  const save = async () => {
    if (!draft) return;
    setBusy(true); setMessage(null);
    try {
      if (adding) {
        await send(cfgApi.vehicles(), 'POST', draft.settings, `차량 ${draft.settings.code} 추가`);
        setMessage('추가했습니다.');
      } else {
        await send(`${cfgApi.vehicles()}/${draft.id}?expectedVersion=${draft.version}`, 'PUT', draft.settings, '설정 화면에서 수정');
        setMessage('저장했습니다.');
      }
      reload();
    } catch (e) {
      const code = e instanceof ApiError ? e.message : String(e);
      setMessage(e instanceof ApiError && e.status === 409
        ? `${explainCode('VERSION_CONFLICT')} (${explainCode(code)})`
        : explainCode(code));
    } finally { setBusy(false); }
  };
  const remove = async () => {
    if (!draft || adding) return;
    if (!window.confirm(`차량 ${draft.settings.code}을 이 지도에서 지웁니다. 마지막으로 보고한 상태도 함께 지워집니다. 진행할까요?`)) return;
    setBusy(true); setMessage(null);
    try {
      await send(`${cfgApi.vehicles()}/${draft.id}`, 'DELETE', undefined, `차량 ${draft.settings.code} 삭제`);
      setMessage('지웠습니다.'); reload();
    } catch (e) {
      setMessage(e instanceof ApiError ? explainCode(e.message) : '지우지 못했습니다.');
    } finally { setBusy(false); }
  };

  return (
    <div className="grid" style={{ gridTemplateColumns: '240px minmax(0,1fr)', gap: 14 }}>
      <div>
        {items.map(v => (
          <button key={v.id} type="button" className={`vehicle-btn${draft?.id === v.id ? ' sel' : ''}`}
            onClick={() => { setDraft(structuredClone(v)); setMessage(null); }}>
            <b>{v.settings.code}</b>
            <span>{v.settings.vType} · {v.settings.name}
              {v.settings.vdaProfileId ? ` · ${profiles?.find(p => p.id === v.settings.vdaProfileId)?.settings.code ?? 'VDA'}` : ''} · v{v.version}</span>
          </button>
        ))}
        {canWrite && (
          <button type="button" className={`vehicle-btn${adding ? ' sel' : ''}`}
            onClick={() => { setDraft(newVehicle()); setMessage(null); }}>
            <b>+ 새 차량</b>
            <span>이 지도에 차량을 추가합니다</span>
          </button>
        )}
      </div>
      <div>
        {!s || !draft ? <p className="hint" style={{ margin: 0 }}>왼쪽에서 차량을 선택하면 파라미터를 편집할 수 있습니다.</p> : (
          <>
            <div style={row}><label htmlFor="v-code">코드</label><input id="v-code" style={field} value={s.code} onChange={e => set({ code: e.target.value })} /></div>
            <div style={row}><label htmlFor="v-name">이름</label><input id="v-name" style={field} value={s.name} onChange={e => set({ name: e.target.value })} /></div>
            <div style={row}>
              <label htmlFor="v-type">차종</label>
              <select id="v-type" style={field} value={s.vType}
                onChange={e => {
                  const vType = e.target.value as VehicleSettings['vType'];
                  // Switching to OHT clears charging, because OHT does not charge here.
                  set(vType === 'OHT' ? { vType, chargingPolicy: 'DISABLED', chargeNodeId: null } : { vType });
                }}>
                <option value="OHT">OHT</option><option value="AGV">AGV</option>
              </select>
            </div>
            <div style={row}>
              <label htmlFor="v-vda">VDA 프로파일</label>
              <div>
                <select id="v-vda" style={field} value={s.vdaProfileId ?? ''} disabled={commType !== 'MQTT' && !s.vdaProfileId}
                  onChange={e => set({ vdaProfileId: e.target.value ? Number(e.target.value) : null })}>
                  <option value="">공란</option>
                  {(profiles ?? []).map(p => <option key={p.id} value={p.id}>{p.settings.code} ({p.settings.topicVersion} · JSON {p.settings.jsonVersion})</option>)}
                </select>
                <span className="hint">{commType === 'MQTT'
                  ? '사용 중인 차량은 프로파일이 필요합니다. 패킷·동작 값은 VDA 프로파일 탭에서 관리합니다.'
                  : `통신 방식이 ${commType ?? '확인 중'}이므로 공란으로 둡니다. MQTT는 운영 옵션에서 선택합니다.`}</span>
              </div>
            </div>
            <div style={row}>
              <label>크기 (mm)</label>
              <div style={{ display: 'flex', gap: 6 }}>
                {(['widthMm', 'lengthMm', 'heightMm'] as const).map(k => (
                  <input key={k} style={field} type="number" aria-label={k} value={s[k]}
                    onChange={e => set({ [k]: Number(e.target.value) } as Partial<VehicleSettings>)} />
                ))}
              </div>
            </div>
            <div style={row}>
              <label htmlFor="v-init">초기 노드</label>
              <select id="v-init" style={field} value={s.initialNodeId} onChange={e => set({ initialNodeId: Number(e.target.value) })}>
                {map.nodes.map(n => <option key={n.id} value={n.id}>{n.code}</option>)}
              </select>
            </div>
            <div style={row}>
              <label htmlFor="v-auth">제어 권한</label>
              <select id="v-auth" style={field} value={s.controlAuthority} onChange={e => set({ controlAuthority: e.target.value as VehicleSettings['controlAuthority'] })}>
                {CONTROL_AUTHORITY.map(o => <option key={o} value={o}>{o}</option>)}
              </select>
            </div>
            <div style={row}>
              <label htmlFor="v-bcr">BCR 정책</label>
              <select id="v-bcr" style={field} value={s.bcrPolicy} onChange={e => set({ bcrPolicy: e.target.value as VehicleSettings['bcrPolicy'] })}>
                {BCR_POLICY.map(o => <option key={o} value={o}>{o}</option>)}
              </select>
            </div>
            <div style={row}>
              <label htmlFor="v-charge">충전 정책</label>
              <div>
                <select id="v-charge" style={field} value={s.chargingPolicy} disabled={s.vType === 'OHT'}
                  onChange={e => set({ chargingPolicy: e.target.value as VehicleSettings['chargingPolicy'] })}>
                  {chargingPolicyOptions(s.vType).map(o => <option key={o} value={o}>{o}</option>)}
                </select>
                {s.vType === 'OHT' && <span className="hint">OHT는 충전을 사용하지 않아 선택할 수 없습니다.</span>}
              </div>
            </div>
            <div style={row}>
              <label htmlFor="v-home">대기 노드</label>
              <div>
                <select id="v-home" style={field} value={s.homeNodeId ?? ''}
                  onChange={e => set({ homeNodeId: e.target.value ? Number(e.target.value) : null })}>
                  <option value="">지정 안 함</option>
                  {map.nodes.filter(n => layerOfNode(map, n.id) === s.vType)
                    .map(n => <option key={n.id} value={n.id}>{n.code}</option>)}
                </select>
                <span className="hint">할 일이 없을 때 머무는 곳입니다. Zone을 비울 때도 이곳을 먼저 씁니다.</span>
              </div>
            </div>
            <div style={row}>
              <label htmlFor="v-speed">주행 속도 (mm/s)</label>
              <div>
                <input id="v-speed" type="number" min={50} max={10000} style={field} value={s.speedMmS ?? ''}
                  onChange={e => set({ speedMmS: e.target.value === '' ? undefined : Number(e.target.value) })} />
                <span className="hint">시뮬레이션에서 구간을 이 속도로 주행합니다. 50~10000.</span>
              </div>
            </div>
            {chargeNodeRequired(s) && (
              <div style={row}>
                <label htmlFor="v-chargenode">충전 노드</label>
                <select id="v-chargenode" style={field} value={s.chargeNodeId ?? ''} onChange={e => set({ chargeNodeId: e.target.value ? Number(e.target.value) : null })}>
                  <option value="">선택</option>
                  {map.nodes.filter(n => layerOfNode(map, n.id) === s.vType)
                    .map(n => <option key={n.id} value={n.id}>{n.code}</option>)}
                </select>
              </div>
            )}
            <div style={row}>
              <label>사용</label>
              <div style={{ display: 'flex', gap: 14, fontSize: 12 }}>
                <label><input type="checkbox" checked={s.enabled} onChange={e => set({ enabled: e.target.checked })} /> 차량 사용</label>
                <label><input type="checkbox" checked={s.assignmentEnabled} onChange={e => set({ assignmentEnabled: e.target.checked })} /> 배차 허용</label>
              </div>
            </div>
            <SaveBar issues={[...vehicleIssues(s), ...vehicleVdaIssues(s, commType)]} canWrite={canWrite} busy={busy}
              message={message} onSave={save} saveLabel={adding ? '추가' : '저장'}
              onRemove={adding ? undefined : remove} />
          </>
        )}
      </div>
    </div>
  );
}

function PortTab({ map, canWrite }: { map: MapData; canWrite: boolean }) {
  const { items, error, reload } = useList<PortSettings>(cfgApi.ports());
  const { items: units } = useList<EquipmentSettings>(opsApi.equipment());
  const [draft, setDraft] = React.useState<Cfg<PortSettings> | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [message, setMessage] = React.useState<string | null>(null);
  React.useEffect(() => { setDraft(null); setMessage(null); }, [items]);
  if (error) return <Failed error={error} onRetry={reload} />;
  if (!items) return <Loading what="포트 설정" />;
  const s = draft?.settings;
  const set = (patch: Partial<PortSettings>) => setDraft(d => d && ({ ...d, settings: { ...d.settings, ...patch } }));
  const adding = draft?.id === 0;
  const newPort = (): Cfg<PortSettings> => ({
    id: 0, version: 0,
    settings: {
      revisionId: map.revisionId, code: '', portType: 'LOAD', accessVehicleType: 'OHT',
      linkedNodeId: map.nodes[0]?.id ?? 0, equipmentUnitId: null,
    },
  });
  const save = async () => {
    if (!draft) return;
    setBusy(true); setMessage(null);
    try {
      if (adding) {
        await send(cfgApi.ports(), 'POST', draft.settings, `포트 ${draft.settings.code} 추가`);
        setMessage('추가했습니다.');
      } else {
        await send(`${cfgApi.ports()}/${draft.id}?expectedVersion=${draft.version}`, 'PUT', draft.settings, '설정 화면에서 수정');
        setMessage('저장했습니다.');
      }
      reload();
    } catch (e) {
      const code = e instanceof ApiError ? e.message : String(e);
      setMessage(e instanceof ApiError && e.status === 409 ? explainCode('VERSION_CONFLICT') : explainCode(code));
    } finally { setBusy(false); }
  };
  const remove = async () => {
    if (!draft || adding) return;
    if (!window.confirm(`포트 ${draft.settings.code}을 지웁니다. 진행 중인 작업이 있으면 거절됩니다.`)) return;
    setBusy(true); setMessage(null);
    try {
      await send(`${cfgApi.ports()}/${draft.id}`, 'DELETE', undefined, `포트 ${draft.settings.code} 삭제`);
      setMessage('지웠습니다.'); reload();
    } catch (e) {
      setMessage(e instanceof ApiError ? explainCode(e.message) : '지우지 못했습니다.');
    } finally { setBusy(false); }
  };
  return (
    <div className="grid" style={{ gridTemplateColumns: '240px minmax(0,1fr)', gap: 14 }}>
      <div>
        {items.map(p => (
          <button key={p.id} type="button" className={`vehicle-btn${draft?.id === p.id ? ' sel' : ''}`}
            onClick={() => { setDraft(structuredClone(p)); setMessage(null); }}>
            <b>{p.settings.code}</b>
            <span>{p.settings.portType} · {p.settings.accessVehicleType} · v{p.version}</span>
          </button>
        ))}
        {canWrite && (
          <button type="button" className={`vehicle-btn${adding ? ' sel' : ''}`}
            onClick={() => { setDraft(newPort()); setMessage(null); }}>
            <b>+ 새 포트</b>
            <span>이 지도에 인수도 포트를 추가합니다</span>
          </button>
        )}
      </div>
      <div>
        {!s || !draft ? <p className="hint" style={{ margin: 0 }}>왼쪽에서 포트를 선택하세요.</p> : (
          <>
            <div style={row}><label htmlFor="p-code">코드</label><input id="p-code" style={field} value={s.code} onChange={e => set({ code: e.target.value })} /></div>
            <div style={row}><label htmlFor="p-type">포트 종류</label><input id="p-type" style={field} value={s.portType} onChange={e => set({ portType: e.target.value })} /></div>
            <div style={row}>
              <label htmlFor="p-access">접근 차종</label>
              <div>
                <select id="p-access" style={field} value={s.accessVehicleType} onChange={e => set({ accessVehicleType: e.target.value as PortSettings['accessVehicleType'] })}>
                  <option value="OHT">OHT</option><option value="AGV">AGV</option>
                </select>
                <span className="hint">출발·도착 포트의 접근 차종이 다르면 단일 차량 작업으로 접수되지 않습니다.</span>
              </div>
            </div>
            <div style={row}>
              <label htmlFor="p-node">연결 노드</label>
              <select id="p-node" style={field} value={s.linkedNodeId} onChange={e => set({ linkedNodeId: Number(e.target.value) })}>
                {map.nodes.map(n => <option key={n.id} value={n.id}>{n.code}</option>)}
              </select>
            </div>
            <div style={row}>
              <label htmlFor="p-eqp">설비</label>
              <div>
                <select id="p-eqp" style={field} value={s.equipmentUnitId ?? ''}
                  onChange={e => set({ equipmentUnitId: e.target.value ? Number(e.target.value) : null })}>
                  <option value="">연결 안 함</option>
                  {(units ?? []).filter(u => u.settings.revisionId === s.revisionId)
                    .map(u => <option key={u.id} value={u.id}>{u.settings.name || u.settings.code} ({u.settings.code})</option>)}
                </select>
                <span className="hint">지도에는 포트 코드 대신 이 설비 이름이 표시됩니다.</span>
              </div>
            </div>
            <SaveBar issues={s.code.trim() ? [] : ['포트 코드를 입력해 주세요.']} canWrite={canWrite} busy={busy}
              message={message} onSave={save} saveLabel={adding ? '추가' : '저장'}
              onRemove={adding ? undefined : remove} />
          </>
        )}
      </div>
    </div>
  );
}

function ConnectionTab({ canWrite }: { canWrite: boolean }) {
  const { items, error, reload } = useList<ConnectionSettings>(cfgApi.connections());
  const [draft, setDraft] = React.useState<Cfg<ConnectionSettings> | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [message, setMessage] = React.useState<string | null>(null);
  React.useEffect(() => { setDraft(null); setMessage(null); }, [items]);
  if (error) return <Failed error={error} onRetry={reload} />;
  if (!items) return <Loading what="연결 설정" />;
  if (items.length === 0) return <Empty title="등록된 연결 프로파일이 없습니다" hint="차량이 접속할 TCP 상대나 MQTT broker를 등록하면 여기에 표시됩니다." />;
  const s = draft?.settings;
  const set = (patch: Partial<ConnectionSettings>) => setDraft(d => d && ({ ...d, settings: { ...d.settings, ...patch } }));
  const save = async () => {
    if (!draft) return;
    setBusy(true); setMessage(null);
    try {
      await send(`${cfgApi.connections()}/${draft.id}?expectedVersion=${draft.version}`, 'PUT', draft.settings, '설정 화면에서 수정');
      setMessage('저장했습니다.'); reload();
    } catch (e) {
      const code = e instanceof ApiError ? e.message : String(e);
      setMessage(e instanceof ApiError && e.status === 409 ? explainCode('VERSION_CONFLICT') : explainCode(code));
    } finally { setBusy(false); }
  };
  return (
    <div className="grid" style={{ gridTemplateColumns: '240px minmax(0,1fr)', gap: 14 }}>
      <div>
        {items.map(c => (
          <button key={c.id} type="button" className={`vehicle-btn${draft?.id === c.id ? ' sel' : ''}`}
            onClick={() => { setDraft(structuredClone(c)); setMessage(null); }}>
            <b>{c.settings.commType} · {c.settings.role}</b>
            <span>{c.settings.host}:{c.settings.port} · v{c.version}</span>
          </button>
        ))}
      </div>
      <div>
        {!s || !draft ? <p className="hint" style={{ margin: 0 }}>왼쪽에서 연결 프로파일을 선택하세요.</p> : (
          <>
            <div style={row}>
              <label htmlFor="c-comm">통신 방식</label>
              <select id="c-comm" style={field} value={s.commType}
                onChange={e => {
                  const commType = e.target.value as ConnectionSettings['commType'];
                  // Role options differ per transport, so reset to the first valid one.
                  set({ commType, role: roleOptions(commType)[0] });
                }}>
                <option value="TCP">TCP</option><option value="MQTT">MQTT</option>
              </select>
            </div>
            <div style={row}>
              <label htmlFor="c-role">역할</label>
              <div>
                <select id="c-role" style={field} value={s.role} onChange={e => set({ role: e.target.value })}>
                  {roleOptions(s.commType).map(o => <option key={o} value={o}>{o}</option>)}
                </select>
                <span className="hint">{s.commType === 'TCP' ? '차량에 접속하거나(CLIENT) 접속을 받습니다(SERVER).' : 'MQTT는 broker 주소만 지정합니다.'}</span>
              </div>
            </div>
            <div style={row}>
              <label htmlFor="c-host">호스트</label>
              <div>
                <input id="c-host" style={field} value={s.host} onChange={e => set({ host: e.target.value })} />
                {/* The same field means two things, and which one it means is the role. */}
                <span className="hint">{s.role === 'SERVER'
                  ? '접속을 받을 주소입니다. 이 서버의 주소이며, 0.0.0.0은 모든 경로에서 받습니다.'
                  : '접속할 상대의 주소입니다.'}</span>
              </div>
            </div>
            <div style={row}><label htmlFor="c-port">포트</label><input id="c-port" type="number" style={field} value={s.port} onChange={e => set({ port: Number(e.target.value) })} /></div>
            <div style={row}>
              <label>활성화</label>
              <div>
                <label style={{ fontSize: 12, color: canWrite ? 'var(--ink2)' : 'var(--mut)' }}>
                  <input type="checkbox" checked={s.enabled} disabled={!canWrite}
                    onChange={e => set({ enabled: e.target.checked })} /> 사용
                </label>
                <span className="hint">{s.role === 'SERVER'
                  ? '사용으로 두면 이 포트를 열고 게이트웨이의 접속을 기다립니다. 접속해 온 쪽은 어느 차량인지 먼저 말해야 합니다.'
                  : '사용으로 두면 보낼 것이 생길 때 이 주소로 접속합니다.'}</span>
              </div>
            </div>
            <SaveBar issues={connectionIssues(s)} canWrite={canWrite} busy={busy} message={message} onSave={save} />
          </>
        )}
      </div>
    </div>
  );
}

function ProtocolTab({ canWrite }: { canWrite: boolean }) {
  const { items, error, reload } = useList<ProtocolSettings>(cfgApi.protocols());
  const [draft, setDraft] = React.useState<Cfg<ProtocolSettings> | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [message, setMessage] = React.useState<string | null>(null);
  React.useEffect(() => { setDraft(null); setMessage(null); }, [items]);
  if (error) return <Failed error={error} onRetry={reload} />;
  if (!items) return <Loading what="프로토콜 설정" />;
  if (items.length === 0) return <Empty title="등록된 프로토콜 프로파일이 없습니다" />;
  const s = draft?.settings;
  const set = (patch: Partial<ProtocolSettings>) => setDraft(d => d && ({ ...d, settings: { ...d.settings, ...patch } }));
  const allowed = s ? versionOptions(s.protoType) : null;
  const save = async () => {
    if (!draft) return;
    setBusy(true); setMessage(null);
    try {
      await send(`${cfgApi.protocols()}/${draft.id}?expectedVersion=${draft.version}`, 'PUT', draft.settings, '설정 화면에서 수정');
      setMessage('저장했습니다.'); reload();
    } catch (e) {
      const code = e instanceof ApiError ? e.message : String(e);
      setMessage(e instanceof ApiError && e.status === 409 ? explainCode('VERSION_CONFLICT') : explainCode(code));
    } finally { setBusy(false); }
  };
  return (
    <div className="grid" style={{ gridTemplateColumns: '240px minmax(0,1fr)', gap: 14 }}>
      <div>
        {items.map(p => (
          <button key={p.id} type="button" className={`vehicle-btn${draft?.id === p.id ? ' sel' : ''}`}
            onClick={() => { setDraft(structuredClone(p)); setMessage(null); }}>
            <b>{p.settings.protoType}</b>
            <span>v{p.settings.protoVersion} · {p.settings.adapterKey} · rev{p.version}</span>
          </button>
        ))}
      </div>
      <div>
        {!s || !draft ? <p className="hint" style={{ margin: 0 }}>왼쪽에서 프로토콜 프로파일을 선택하세요.</p> : (
          <>
            <div style={row}>
              <label htmlFor="pr-type">프로토콜</label>
              <select id="pr-type" style={field} value={s.protoType}
                onChange={e => {
                  const protoType = e.target.value as ProtocolSettings['protoType'];
                  const list = versionOptions(protoType);
                  // VDA5050 has a fixed version list, so snap to a supported value when switching.
                  set({ protoType, protoVersion: list ? (list.includes(s.protoVersion) ? s.protoVersion : list[list.length - 1]) : s.protoVersion });
                }}>
                {PROTO_TYPES.map(o => <option key={o} value={o}>{o}</option>)}
              </select>
            </div>
            <div style={row}>
              <label htmlFor="pr-ver">버전</label>
              <div>
                {allowed
                  ? <select id="pr-ver" style={field} value={s.protoVersion} onChange={e => set({ protoVersion: e.target.value })}>
                      {allowed.map(o => <option key={o} value={o}>{o}</option>)}
                    </select>
                  : <input id="pr-ver" style={field} value={s.protoVersion} onChange={e => set({ protoVersion: e.target.value })} />}
                <span className="hint">{allowed ? `지원 버전: ${allowed.join(', ')}` : '이 프로토콜은 버전 문자열을 자유롭게 지정합니다.'}</span>
              </div>
            </div>
            <div style={row}><label htmlFor="pr-key">어댑터 키</label><input id="pr-key" style={field} value={s.adapterKey} onChange={e => set({ adapterKey: e.target.value })} /></div>
            <SaveBar issues={protocolIssues(s)} canWrite={canWrite} busy={busy} message={message} onSave={save} />
          </>
        )}
      </div>
    </div>
  );
}

// The site-wide options that used to sit here moved to the system options screen: they are not
// one resource's parameters, and editing them beside a vehicle's settings hid how far they reach.

const VDA_FIELDS: { key: keyof VdaProfileSettings; label: string; hint?: string }[] = [
  { key: 'code', label: '코드', hint: '차량 파라미터에서 이 프로파일을 고를 때 쓰는 이름입니다.' },
  { key: 'interfaceName', label: '인터페이스', hint: '토픽 첫 단계 값입니다. 예: vda5050, udp' },
  { key: 'topicVersion', label: '토픽 버전', hint: '예: v2, v3' },
  { key: 'manufacturer', label: '제조사' },
  { key: 'jsonVersion', label: 'JSON 버전', hint: '토픽 버전과 별개입니다. 예: 1.0.0, 3.0.0' },
  { key: 'sourceAction', label: '출발지 동작', hint: '출발지에서 화물을 가져갈 때 보내는 동작입니다. 예: pick' },
  { key: 'destAction', label: '목적지 동작', hint: '목적지에 화물을 내려놓을 때 보내는 동작입니다. 예: drop' },
];

function VdaProfileTab({ canWrite }: { canWrite: boolean }) {
  const { items, error, reload } = useList<VdaProfileSettings>(cfgApi.vdaProfiles());
  const [draft, setDraft] = React.useState<Cfg<VdaProfileSettings> | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [message, setMessage] = React.useState<string | null>(null);
  React.useEffect(() => { setDraft(null); setMessage(null); }, [items]);
  if (error) return <Failed error={error} onRetry={reload} />;
  if (!items) return <Loading what="VDA 프로파일" />;
  if (items.length === 0) return <Empty title="등록된 VDA 프로파일이 없습니다" />;
  const s = draft?.settings;
  const set = (patch: Partial<VdaProfileSettings>) => setDraft(d => d && ({ ...d, settings: { ...d.settings, ...patch } }));
  const save = async () => {
    if (!draft) return;
    setBusy(true); setMessage(null);
    try {
      await send(`${cfgApi.vdaProfiles()}/${draft.id}?expectedVersion=${draft.version}`, 'PUT', draft.settings, '설정 화면에서 수정');
      setMessage('저장했습니다. 이 프로파일을 쓰는 모든 차량에 적용됩니다.'); reload();
    } catch (e) {
      const code = e instanceof ApiError ? e.message : String(e);
      setMessage(e instanceof ApiError && e.status === 409 ? explainCode('VERSION_CONFLICT') : explainCode(code));
    } finally { setBusy(false); }
  };
  return (
    <div className="grid" style={{ gridTemplateColumns: '240px minmax(0,1fr)', gap: 14 }}>
      <div>
        {items.map(p => (
          <button key={p.id} type="button" className={`vehicle-btn${draft?.id === p.id ? ' sel' : ''}`}
            onClick={() => { setDraft(structuredClone(p)); setMessage(null); }}>
            <b>{p.settings.code}</b>
            <span>{p.settings.manufacturer} · {p.settings.topicVersion} · JSON {p.settings.jsonVersion} · v{p.version}</span>
          </button>
        ))}
      </div>
      <div>
        {!s || !draft ? <p className="hint" style={{ margin: 0 }}>왼쪽에서 VDA 프로파일을 선택하세요.</p> : (
          <>
            {VDA_FIELDS.map(f => (
              <div key={f.key} style={row}>
                <label htmlFor={`vda-${f.key}`}>{f.label}</label>
                <div>
                  <input id={`vda-${f.key}`} style={field} value={s[f.key]}
                    onChange={e => set({ [f.key]: e.target.value } as Partial<VdaProfileSettings>)} />
                  {f.hint && <span className="hint">{f.hint}</span>}
                </div>
              </div>
            ))}
            <SaveBar issues={vdaProfileIssues(s)} canWrite={canWrite} busy={busy} message={message} onSave={save} />
          </>
        )}
      </div>
    </div>
  );
}

/**
 * The dialects this site speaks.
 *
 * <p>A mapping document says how a command leaves this controller for one family of vehicles and
 * how what comes back is read. It was the one piece of configuration that lived only as a file on
 * the server's disk: no screen could change it, no version could be compared, and nothing recorded
 * who had put it there. It is kept with the rest now.
 *
 * <p>The documents shipped with the server stay underneath as the built-in set. A site's own
 * document of the same name wins, and removing it brings the shipped one back -- which is why the
 * listing names where each one in use comes from rather than only what is installed.
 */
function MappingTab({ canWrite }: { canWrite: boolean }) {
  const [items, setItems] = React.useState<InstalledMapping[] | null>(null);
  const [available, setAvailable] = React.useState<Record<string, string>>({});
  const [error, setError] = React.useState<unknown>(null);
  const [chosen, setChosen] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [message, setMessage] = React.useState<{ tone: string; text: string } | null>(null);
  const [tick, setTick] = React.useState(0);
  const reload = () => setTick(t => t + 1);
  React.useEffect(() => {
    const ac = new AbortController();
    setError(null);
    Promise.all([
      get<InstalledMapping[]>(cfgApi.mappings(), ac.signal),
      get<Record<string, string>>(cfgApi.mappingsAvailable(), ac.signal),
    ]).then(([list, names]) => { setItems(list); setAvailable(names); })
      .catch(cause => { if ((cause as Error).name !== 'AbortError') setError(cause); });
    return () => ac.abort();
  }, [tick]);

  // A refusal carries what is wrong with the document, which is the only useful part of it.
  const explain = (cause: unknown) => {
    if (!(cause instanceof ApiError)) return cause instanceof SyntaxError ? 'JSON 형식이 아닙니다.' : '처리하지 못했습니다.';
    const code = cause.message;
    return code.startsWith('MAPPING_REJECTED:') ? `문서를 받아들이지 않았습니다 — ${code.slice('MAPPING_REJECTED:'.length).trim()}`
      : explainCode(code);
  };

  async function upload(file: File) {
    setBusy(true); setMessage(null);
    try {
      // Sent as written: the server keeps the document verbatim, so reformatting it here would
      // hand back a file that no longer matches the one on somebody's disk.
      const saved = await sendText<InstalledMapping>(cfgApi.mappings(), 'POST', await file.text(),
        `매핑 문서 올리기 (${file.name})`);
      setMessage({
        tone: 'ok',
        text: `${saved.code}을(를) ${saved.version === 0 ? '등록했습니다' : `고쳤습니다 (rev${saved.version})`}`
          + ` — ${saved.adapter}, 명령 ${saved.commands}종, 수신 규칙 ${saved.inboundRules}개.`
          + ' 이 이름을 어댑터 키로 쓰는 프로토콜 프로파일에 바로 적용됩니다.',
      });
      setChosen(saved.code); reload();
    } catch (cause) {
      setMessage({ tone: 'er', text: explain(cause) });
    } finally { setBusy(false); }
  }
  async function remove(m: InstalledMapping) {
    if (!window.confirm(`매핑 문서 ${m.code}을(를) 내립니다. 같은 이름이 서버에 기본으로 들어 있으면 그것이 다시 쓰입니다. 진행할까요?`)) return;
    setBusy(true); setMessage(null);
    try {
      await send(cfgApi.mapping(m.code), 'DELETE', undefined, `매핑 문서 ${m.code} 내리기`);
      setMessage({ tone: 'ok', text: `${m.code}을(를) 내렸습니다.` });
      setChosen(null); reload();
    } catch (cause) {
      setMessage({ tone: 'er', text: explain(cause) });
    } finally { setBusy(false); }
  }
  function download(m: InstalledMapping) {
    const url = URL.createObjectURL(new Blob([m.document], { type: 'application/json' }));
    const link = window.document.createElement('a');
    link.href = url; link.download = `${m.code}.json`;
    link.click();
    URL.revokeObjectURL(url);
  }

  if (error) return <Failed error={error} onRetry={reload} />;
  if (!items) return <Loading what="매핑 문서" />;
  const selected = items.find(m => m.code === chosen) ?? null;
  const shipped = Object.keys(available).filter(code => !items.some(m => m.code === code));
  return (
    <div>
      {message && <p style={{ margin: '0 0 9px', fontSize: 12 }}>
        <Chip tone={message.tone}>{message.tone === 'er' ? '거부' : '완료'}</Chip> {message.text}
      </p>}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
        <label style={{ ...btn, cursor: canWrite && !busy ? 'pointer' : 'default', opacity: canWrite ? 1 : 0.6 }}>
          문서 올리기
          <input type="file" accept="application/json,.json" disabled={!canWrite || busy} style={{ display: 'none' }}
            onChange={e => { const f = e.target.files?.[0]; e.target.value = ''; if (f) void upload(f); }} />
        </label>
        <span className="hint" style={{ margin: 0 }}>
          문서 안의 <span className="mono">mapping</span> 값이 이름입니다. 같은 이름을 다시 올리면 그 문서를 고치는 것이 됩니다.
        </span>
      </div>
      {items.length === 0
        ? <p className="hint" style={{ margin: '0 0 10px' }}>이 사이트가 올린 매핑 문서는 아직 없습니다. 아래는 서버에 기본으로 들어 있는 문서입니다.</p>
        : (
          <Grid cols="160px 130px 96px 1fr 130px 172px"
            head={['이름', '어댑터', '명령·수신', '설명', '수정', '']}>
            {items.map(m => (
              <Row key={m.id} selected={m.code === chosen}>
                <span className="mono">{m.code}</span>
                <span className="mono">{m.adapter}</span>
                <span className="mono" title="보낼 수 있는 명령 종류 · 읽을 수 있는 수신 규칙">{m.commands} · {m.inboundRules}</span>
                <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{m.notes ?? ''}</span>
                <span className="mono">{m.updatedBy ?? ''} · rev{m.version}</span>
                <span style={{ display: 'flex', gap: 6 }}>
                  <button type="button" onClick={() => setChosen(m.code === chosen ? null : m.code)}
                    style={{ ...btn, padding: '3px 9px' }}>{m.code === chosen ? '닫기' : '보기'}</button>
                  <button type="button" disabled={busy} onClick={() => download(m)}
                    style={{ ...btn, padding: '3px 9px' }}>받기</button>
                  <button type="button" disabled={!canWrite || busy} onClick={() => void remove(m)}
                    title="이 이름을 어댑터 키로 쓰는 프로파일을 가리키는 차량이 있으면 거부됩니다."
                    style={{
                      ...btn, padding: '3px 9px', borderColor: 'var(--crit)', color: 'var(--crit)',
                      cursor: canWrite && !busy ? 'pointer' : 'default',
                    }}>내리기</button>
                </span>
              </Row>
            ))}
          </Grid>
        )}
      {shipped.length > 0 && (
        <p className="hint" style={{ marginTop: 10 }}>
          서버 기본 문서: {shipped.map(code => `${code} (${available[code]})`).join(', ')}.
          같은 이름으로 올리면 이 사이트에서는 올린 쪽이 쓰입니다.
        </p>
      )}
      {selected && (
        <div style={{ marginTop: 12 }}>
          <div className="hint" style={{ marginBottom: 6 }}>{selected.code} — 저장된 그대로입니다. 고칠 때는 내려받아 고친 뒤 다시 올립니다.</div>
          <textarea readOnly value={selected.document} spellCheck={false}
            style={{ ...field, height: 320, fontFamily: 'var(--mono, monospace)', fontSize: 11, lineHeight: 1.5 }} />
        </div>
      )}
    </div>
  );
}

/**
 * Which equipment is wired to what.
 *
 * <p>The same two profiles a vehicle uses, because a stocker behind a PLC is the same arrangement
 * as an AGV behind one. What differs is the dialect: an equipment document says what a stocker can
 * report, which is not what a vehicle can, so the server refuses a vehicle's document here.
 *
 * <p>Equipment is read by polling, so the link has to be one this controller dials. A gateway that
 * dials in has no address to poll and is refused.
 */
function EquipmentLinkTab({ canWrite }: { canWrite: boolean }) {
  const { items, error, reload } = useList<EquipmentCommunicationSettings>(equipmentApi.communications());
  const units = useList<EquipmentSettings>(opsApi.equipment());
  const [draft, setDraft] = React.useState<Cfg<EquipmentCommunicationSettings> | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [message, setMessage] = React.useState<string | null>(null);
  React.useEffect(() => { setDraft(null); setMessage(null); }, [items]);
  if (error) return <Failed error={error} onRetry={reload} />;
  if (!items) return <Loading what="설비 통신 설정" />;
  const named = (unitId: number) => units.items?.find(u => u.id === unitId)?.settings.code ?? `#${unitId}`;
  const s = draft?.settings;
  const set = (patch: Partial<EquipmentCommunicationSettings>) =>
    setDraft(d => d && ({ ...d, settings: { ...d.settings, ...patch } }));
  const save = async () => {
    if (!draft) return;
    setBusy(true); setMessage(null);
    try {
      await send(`${equipmentApi.communications()}/${draft.id}?expectedVersion=${draft.version}`, 'PUT',
        draft.settings, '설정 화면에서 수정');
      setMessage('저장했습니다.'); reload();
    } catch (e) {
      const code = e instanceof ApiError ? e.message : String(e);
      setMessage(e instanceof ApiError && e.status === 409 && code === 'VERSION_CONFLICT'
        ? explainCode('VERSION_CONFLICT') : explainCode(code));
    } finally { setBusy(false); }
  };
  return (
    <div className="grid" style={{ gridTemplateColumns: '240px minmax(0,1fr)', gap: 14 }}>
      <div>
        {items.length === 0
          ? <p className="hint" style={{ margin: 0 }}>설정된 설비 통신이 없습니다.</p>
          : items.map(c => (
            <button key={c.id} type="button" className={`vehicle-btn${draft?.id === c.id ? ' sel' : ''}`}
              onClick={() => { setDraft(structuredClone(c)); setMessage(null); }}>
              <b>{named(c.settings.unitId)}</b>
              <span>{c.settings.externalUnitId} · {c.settings.enabled ? '사용' : '사용 안 함'} · rev{c.version}</span>
            </button>
          ))}
      </div>
      <div>
        {!s || !draft ? (
          <p className="hint" style={{ margin: 0 }}>
            왼쪽에서 설비 통신을 선택하세요. 설비마다 연결·프로토콜 프로파일과 저쪽이 부르는 이름을 지정하면,
            그 설비가 말하는 것을 받아 반송 판단에 씁니다. 설정하지 않은 설비는 거부하지 않고 통과시킵니다.
          </p>
        ) : (
          <>
            <div style={row}>
              <label htmlFor="ec-ext">외부 설비 ID</label>
              <div>
                <input id="ec-ext" style={field} value={s.externalUnitId}
                  onChange={e => set({ externalUnitId: e.target.value })} />
                <span className="hint">설비 쪽이 이 설비를 부르는 이름입니다. 매핑 문서가 신원 슬롯에서 읽는 값과 같아야 합니다.</span>
              </div>
            </div>
            <div style={row}>
              <label htmlFor="ec-conn">연결 프로파일</label>
              <div>
                <input id="ec-conn" type="number" style={field} value={s.connectionProfileId}
                  onChange={e => set({ connectionProfileId: Number(e.target.value) })} />
                <span className="hint">연결 탭의 TCP·CLIENT 프로파일이어야 합니다. 폴링으로 읽으므로 접속을 받는 구성은 쓸 수 없습니다.</span>
              </div>
            </div>
            <div style={row}>
              <label htmlFor="ec-proto">프로토콜 프로파일</label>
              <div>
                <input id="ec-proto" type="number" style={field} value={s.protocolProfileId}
                  onChange={e => set({ protocolProfileId: Number(e.target.value) })} />
                <span className="hint">어댑터 키가 <span className="mono">EQUIPMENT_WORD_V1</span> 매핑 문서를 가리켜야 합니다.</span>
              </div>
            </div>
            <div style={row}>
              <label>활성화</label>
              <div>
                <label style={{ fontSize: 12, color: canWrite ? 'var(--ink2)' : 'var(--mut)' }}>
                  <input type="checkbox" checked={s.enabled} disabled={!canWrite}
                    onChange={e => set({ enabled: e.target.checked })} /> 사용
                </label>
                <span className="hint">사용으로 두면 이 설비를 주기적으로 읽습니다. 읽은 것이 없으면 그 설비로 가는 작업을 거부합니다.</span>
              </div>
            </div>
            <SaveBar issues={equipmentLinkIssues(s)} canWrite={canWrite} busy={busy} message={message} onSave={save} />
          </>
        )}
      </div>
    </div>
  );
}
