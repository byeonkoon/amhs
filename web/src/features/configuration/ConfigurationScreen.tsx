import React from 'react';
import {
  ApiError, cfgApi, get, hasWriteToken, send, setWriteToken,
  type Cfg, type ConnectionSettings, type MapData, type OperationOptions, type PortSettings, type ProtocolSettings,
  type VdaProfileSettings, type VehicleSettings,
} from '../../api';
import { Chip, Empty, Failed, Loading } from '../../shell/States';
import {
  ADAPTER_DISABLED_REASON, BCR_POLICY, COMM_TYPES, CONTROL_AUTHORITY, chargeNodeRequired, chargingPolicyOptions,
  connectionIssues, explainCode, operationIssues, protocolIssues, PROTO_TYPES, roleOptions, vdaProfileIssues,
  vehicleIssues, vehicleVdaIssues, versionOptions,
} from './rules';

type Tab = 'operation' | 'vehicles' | 'vda' | 'ports' | 'connections' | 'protocols';
const TABS: { key: Tab; label: string }[] = [
  { key: 'operation', label: '운영 옵션' }, { key: 'vehicles', label: '차량' }, { key: 'vda', label: 'VDA 프로파일' },
  { key: 'ports', label: '포트' },
  { key: 'connections', label: '연결' }, { key: 'protocols', label: '프로토콜' },
];

export function ConfigurationScreen({ map }: { map: MapData }) {
  const [tab, setTab] = React.useState<Tab>('vehicles');
  const [tokenSet, setTokenSet] = React.useState(hasWriteToken());
  return (
    <>
      <TokenBar tokenSet={tokenSet} onChange={setTokenSet} />
      <div className="card">
        <div className="ch" style={{ gap: 4 }}>
          {TABS.map(t => (
            <button key={t.key} type="button" onClick={() => setTab(t.key)} aria-pressed={tab === t.key}
              style={{
                border: '1px solid var(--ln2)', borderRadius: 5, padding: '5px 13px', fontSize: 12, cursor: 'pointer',
                background: tab === t.key ? 'var(--acc)' : 'var(--srf)', color: tab === t.key ? '#fff' : 'var(--ink2)',
              }}>{t.label}</button>
          ))}
          <span className="aux">저장은 관리자 토큰과 유지보수 상태가 필요합니다</span>
        </div>
        <div className="cb">
          {tab === 'operation' && <OperationTab canWrite={tokenSet} />}
          {tab === 'vehicles' && <VehicleTab map={map} canWrite={tokenSet} />}
          {tab === 'vda' && <VdaProfileTab canWrite={tokenSet} />}
          {tab === 'ports' && <PortTab map={map} canWrite={tokenSet} />}
          {tab === 'connections' && <ConnectionTab canWrite={tokenSet} />}
          {tab === 'protocols' && <ProtocolTab canWrite={tokenSet} />}
        </div>
      </div>
    </>
  );
}

/** The token lives in memory for this tab only. It is never stored and never shown back. */
function TokenBar({ tokenSet, onChange }: { tokenSet: boolean; onChange: (v: boolean) => void }) {
  const [value, setValue] = React.useState('');
  return (
    <div className="card">
      <div className="cb" style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
        <b style={{ fontSize: 12.5 }}>설정 변경 토큰</b>
        {tokenSet
          ? <>
            <Chip tone="ok">입력됨</Chip>
            <span className="hint" style={{ margin: 0 }}>이 탭에서만 유지되며 저장하지 않습니다. 새로고침하면 다시 입력해야 합니다.</span>
            <button type="button" style={btn} onClick={() => { setWriteToken(null); setValue(''); onChange(false); }}>지우기</button>
          </>
          : <>
            <input type="password" value={value} onChange={e => setValue(e.target.value)}
              placeholder="관리자 토큰" aria-label="설정 변경 토큰"
              style={{ border: '1px solid var(--ln2)', borderRadius: 5, padding: '6px 10px', fontSize: 12, minWidth: 260 }} />
            <button type="button" style={btn} disabled={!value.trim()}
              onClick={() => { setWriteToken(value); setValue(''); onChange(true); }}>적용</button>
            <span className="hint" style={{ margin: 0 }}>토큰이 없으면 조회만 가능합니다.</span>
          </>}
      </div>
    </div>
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

function SaveBar({ issues, canWrite, busy, message, onSave }: {
  issues: string[]; canWrite: boolean; busy: boolean; message: string | null; onSave: () => void;
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
        {busy ? '저장 중…' : '저장'}
      </button>
      {!canWrite && <span className="hint" style={{ marginLeft: 10 }}>토큰을 입력하면 저장할 수 있습니다.</span>}
    </div>
  );
}

function VehicleTab({ map, canWrite }: { map: MapData; canWrite: boolean }) {
  const { items, error, reload } = useList<VehicleSettings>(cfgApi.vehicles());
  const { item: option } = useOne<OperationOptions>(cfgApi.operation());
  const { items: profiles } = useList<VdaProfileSettings>(cfgApi.vdaProfiles());
  const commType = option?.settings.commType ?? null;
  const [draft, setDraft] = React.useState<Cfg<VehicleSettings> | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [message, setMessage] = React.useState<string | null>(null);
  React.useEffect(() => { setDraft(null); setMessage(null); }, [items]);
  if (error) return <Failed error={error} onRetry={reload} />;
  if (!items) return <Loading what="차량 설정" />;
  if (items.length === 0) return <Empty title="등록된 차량 설정이 없습니다" />;
  const s = draft?.settings;
  const set = (patch: Partial<VehicleSettings>) => setDraft(d => d && ({ ...d, settings: { ...d.settings, ...patch } }));

  const save = async () => {
    if (!draft) return;
    setBusy(true); setMessage(null);
    try {
      await send(`${cfgApi.vehicles()}/${draft.id}?expectedVersion=${draft.version}`, 'PUT', draft.settings, '설정 화면에서 수정');
      setMessage('저장했습니다.'); reload();
    } catch (e) {
      const code = e instanceof ApiError ? e.message : String(e);
      setMessage(e instanceof ApiError && e.status === 409
        ? `${explainCode('VERSION_CONFLICT')} (${explainCode(code)})`
        : explainCode(code));
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
            {chargeNodeRequired(s) && (
              <div style={row}>
                <label htmlFor="v-chargenode">충전 노드</label>
                <select id="v-chargenode" style={field} value={s.chargeNodeId ?? ''} onChange={e => set({ chargeNodeId: e.target.value ? Number(e.target.value) : null })}>
                  <option value="">선택</option>
                  {map.nodes.map(n => <option key={n.id} value={n.id}>{n.code}</option>)}
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
            <SaveBar issues={[...vehicleIssues(s), ...vehicleVdaIssues(s, commType)]} canWrite={canWrite} busy={busy} message={message} onSave={save} />
          </>
        )}
      </div>
    </div>
  );
}

function PortTab({ map, canWrite }: { map: MapData; canWrite: boolean }) {
  const { items, error, reload } = useList<PortSettings>(cfgApi.ports());
  const [draft, setDraft] = React.useState<Cfg<PortSettings> | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [message, setMessage] = React.useState<string | null>(null);
  React.useEffect(() => { setDraft(null); setMessage(null); }, [items]);
  if (error) return <Failed error={error} onRetry={reload} />;
  if (!items) return <Loading what="포트 설정" />;
  if (items.length === 0) return <Empty title="등록된 포트 설정이 없습니다" />;
  const s = draft?.settings;
  const set = (patch: Partial<PortSettings>) => setDraft(d => d && ({ ...d, settings: { ...d.settings, ...patch } }));
  const save = async () => {
    if (!draft) return;
    setBusy(true); setMessage(null);
    try {
      await send(`${cfgApi.ports()}/${draft.id}?expectedVersion=${draft.version}`, 'PUT', draft.settings, '설정 화면에서 수정');
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
            <b>{p.settings.code}</b>
            <span>{p.settings.portType} · {p.settings.accessVehicleType} · v{p.version}</span>
          </button>
        ))}
      </div>
      <div>
        {!s || !draft ? <p className="hint" style={{ margin: 0 }}>왼쪽에서 포트를 선택하세요.</p> : (
          <>
            <div style={row}><label htmlFor="p-code">코드</label><input id="p-code" style={field} value={s.code} onChange={e => set({ code: e.target.value })} /></div>
            <div style={row}><label htmlFor="p-type">포트 종류</label><input id="p-type" style={field} value={s.portType} onChange={e => set({ portType: e.target.value })} /></div>
            <div style={row}><label htmlFor="p-maker">제조 구분</label><input id="p-maker" style={field} value={s.makerType} onChange={e => set({ makerType: e.target.value })} /></div>
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
            <SaveBar issues={s.code.trim() ? [] : ['포트 코드를 입력해 주세요.']} canWrite={canWrite} busy={busy} message={message} onSave={save} />
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
            <div style={row}><label htmlFor="c-host">호스트</label><input id="c-host" style={field} value={s.host} onChange={e => set({ host: e.target.value })} /></div>
            <div style={row}><label htmlFor="c-port">포트</label><input id="c-port" type="number" style={field} value={s.port} onChange={e => set({ port: Number(e.target.value) })} /></div>
            <div style={row}>
              <label>활성화</label>
              <div>
                <label style={{ fontSize: 12, color: 'var(--mut)' }}>
                  <input type="checkbox" checked={false} disabled /> 사용
                </label>
                <span className="hint">{ADAPTER_DISABLED_REASON}</span>
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

function OperationTab({ canWrite }: { canWrite: boolean }) {
  const { item, error, reload } = useOne<OperationOptions>(cfgApi.operation());
  const [draft, setDraft] = React.useState<OperationOptions | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [message, setMessage] = React.useState<string | null>(null);
  React.useEffect(() => { setDraft(item ? { ...item.settings } : null); }, [item]);
  if (error) return <Failed error={error} onRetry={reload} />;
  if (!item || !draft) return <Loading what="운영 옵션" />;
  const save = async () => {
    setBusy(true); setMessage(null);
    try {
      await send(`${cfgApi.operation()}?expectedVersion=${item.version}`, 'PUT', draft, '설정 화면에서 수정');
      setMessage('저장했습니다.'); reload();
    } catch (e) {
      setMessage(explainCode(e instanceof ApiError ? e.message : String(e)));
    } finally { setBusy(false); }
  };
  return (
    <div style={{ maxWidth: 560 }}>
      <div style={row}>
        <label htmlFor="o-comm">통신 방식</label>
        <div>
          <select id="o-comm" style={field} value={draft.commType}
            onChange={e => setDraft({ ...draft, commType: e.target.value as OperationOptions['commType'] })}>
            {COMM_TYPES.map(o => <option key={o} value={o}>{o}</option>)}
          </select>
          <span className="hint">사이트의 모든 차량에 적용됩니다. MQTT이면 차량마다 VDA 프로파일을 지정합니다. MQTT에서 다른 방식으로 바꾸려면 차량의 프로파일을 먼저 비워야 합니다.</span>
        </div>
      </div>
      <div style={row}>
        <label htmlFor="o-nodes">허가 노드 수</label>
        <div>
          <input id="o-nodes" type="number" style={field} value={draft.nodeCount}
            onChange={e => setDraft({ ...draft, nodeCount: Number(e.target.value) })} />
          <span className="hint">한 번의 진입 허가에 포함할 수 있는 최대 노드 수입니다(1~100).</span>
        </div>
      </div>
      <SaveBar issues={operationIssues(draft)} canWrite={canWrite} busy={busy} message={message} onSave={save} />
    </div>
  );
}

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
