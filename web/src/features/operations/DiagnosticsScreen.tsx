import React from 'react';
import {
  ApiError, opsApi, send, hasWriteToken,
  type DeviceCommand, type CommandStatus, type MapData,
  type VehicleReportResult, type VehicleSnapshot,
} from '../../api';
import { Chip, Empty } from '../../shell/States';

/** Command states, described by what they permit rather than by their name. */
const COMMAND_STATE: Record<CommandStatus, { tone: string; label: string; note: string }> = {
  PENDING: { tone: 'id', label: '대기', note: '아직 회선에 나가지 않았습니다. 다시 보내도 중복이 생기지 않습니다.' },
  DISPATCHING: { tone: 'wr', label: '결과 불명', note: '송신이 시작됐고 결과를 모릅니다. 자동으로 다시 보내지 않습니다.' },
  SENT: { tone: 'wr', label: '송신됨', note: '회선에 나갔습니다. 장비가 이미 수행 중일 수 있어 다시 보내지 않습니다.' },
  ACCEPTED: { tone: 'ok', label: '수신 확인', note: '장비가 받았다고 응답했습니다. 수행 완료와는 다릅니다.' },
  FAILED: { tone: 'er', label: '미도달', note: '회선에 나가지 못했음이 확인됐습니다. 지연 후 다시 시도합니다.' },
  WITHDRAWN: { tone: 'id', label: '철회', note: '나가기 전에 작업이 멈춰 거둬들였습니다. 보내지 않습니다.' },
};

/** What one flush of the outbox did. */
type Publication = { claimed: number; delivered: number; notDelivered: number; inDoubt: number };

export function DiagnosticsScreen(props: {
  map: MapData; revisionId: number;
  vehicles: VehicleSnapshot[]; inDoubt: DeviceCommand[];
  onChanged: () => void;
}) {
  return (
    <>
      <InDoubtCommands commands={props.inDoubt} onChanged={props.onChanged} />
      <VehicleReportPanel map={props.map} revisionId={props.revisionId}
        vehicles={props.vehicles} onChanged={props.onChanged} />
    </>
  );
}

function InDoubtCommands({ commands, onChanged }: { commands: DeviceCommand[]; onChanged: () => void }) {
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<string | null>(null);

  async function publish() {
    setBusy(true); setResult(null);
    try {
      const p = await send<Publication>(opsApi.publish(), 'POST', undefined, '대기 명령 발행');
      setResult(p.claimed === 0
        ? '발행할 대기 명령이 없습니다.'
        : `선점 ${p.claimed} · 전달 ${p.delivered} · 미도달 ${p.notDelivered} · 결과 불명 ${p.inDoubt}`);
      onChanged();
    } catch (cause) {
      setResult(cause instanceof ApiError ? cause.message : '발행에 실패했습니다.');
    } finally { setBusy(false); }
  }

  return (
    <section className="card">
      <div className="ch">
        <h2>결과 불명 장비 명령</h2>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
          <span className="aux">자동으로 정리되지 않는 명령입니다. 운전자 판단이 필요합니다</span>
          <button type="button" onClick={publish} disabled={busy || !hasWriteToken()} style={ghost}>
            {busy ? '발행 중…' : '대기 명령 발행'}
          </button>
        </div>
      </div>
      {result && <div className="cb" style={{ paddingBottom: 0 }}><p className="hint" style={{ margin: 0 }}>{result}</p></div>}
      {commands.length === 0
        ? <Empty title="결과가 불명한 명령이 없습니다"
            hint="송신 결과를 알 수 없는 명령만 여기 남습니다. 도달하지 못한 것이 확인된 명령은 자동으로 재시도됩니다." />
        : (
          <div className="tbl-scroll">
            <table className="t">
              <thead>
                <tr><th>명령</th><th>작업</th><th>종류</th><th>상태</th><th className="mono">시도</th><th>마지막 결과</th><th>발생</th></tr>
              </thead>
              <tbody>
                {commands.map(c => {
                  const s = COMMAND_STATE[c.status];
                  return (
                    <tr key={c.id}>
                      <td className="mono">{c.commandId}</td>
                      <td className="mono">{c.orderId === null ? 'Zone 퇴출' : `#${c.orderId}`}</td>
                      <td className="mono">{c.commandType}</td>
                      <td><Chip tone={s.tone}>{s.label}</Chip></td>
                      <td className="mono">{c.attempt}</td>
                      <td className="mono">{c.lastError ?? c.lastOutcome ?? '—'}</td>
                      <td className="mono">{new Date(c.createdAt).toLocaleString('ko-KR', { hour12: false })}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      <p className="hint" style={{ padding: '0 15px 14px' }}>
        이동 명령을 장비가 이미 수행 중인데 한 번 더 보내는 것이 가장 위험하므로, 결과가 불명한 송신은 성공이나
        실패 어느 쪽으로도 임의 해석하지 않습니다. 장비 수신 확인이 도착하면 해소됩니다.
      </p>
    </section>
  );
}

/**
 * The commissioning path for vehicle state. It exists because no device adapter does this yet,
 * and every value entered here is recorded as simulated so it is never read back as evidence
 * from a vehicle.
 */
function VehicleReportPanel(props: {
  map: MapData; revisionId: number; vehicles: VehicleSnapshot[]; onChanged: () => void;
}) {
  const [vehicleId, setVehicleId] = React.useState<number | null>(props.vehicles[0]?.id ?? null);
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<VehicleReportResult | null>(null);
  const [error, setError] = React.useState<string | null>(null);

  const selected = props.vehicles.find(v => v.id === vehicleId) ?? null;
  const [draft, setDraft] = React.useState<{ nodeId: number | null; online: boolean; remote: boolean; idle: boolean; carrierPresent: boolean }>(
    { nodeId: null, online: false, remote: false, idle: true, carrierPresent: false });

  // Selecting a vehicle loads what it last reported, so a report edits reality rather than replacing it blind.
  React.useEffect(() => {
    if (!selected) return;
    setDraft({
      nodeId: selected.nodeId, online: selected.online, remote: selected.remote,
      idle: selected.idle, carrierPresent: selected.carrierPresent,
    });
    setResult(null); setError(null);
  }, [vehicleId]);

  const reachable = props.map.nodes;

  async function submit() {
    if (!selected) return;
    setBusy(true); setError(null); setResult(null);
    try {
      const body = { ...draft, source: 'SIMULATED' };
      const res = await send<VehicleReportResult>(opsApi.reportVehicle(props.revisionId, selected.id), 'PUT', body, '운전자 모의 보고');
      setResult(res);
      props.onChanged();
    } catch (cause) {
      setError(cause instanceof ApiError ? explainReport(cause.message) : '보고에 실패했습니다.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <div className="ch">
        <h2>차량 상태 보고</h2>
        <span className="aux">장비 어댑터가 없는 동안의 수동 경로입니다. 입력한 값은 모의 보고로 기록됩니다</span>
      </div>
      <div className="cb" style={{ display: 'grid', gap: 12 }}>
        {props.vehicles.length === 0
          ? <Empty title="등록된 차량이 없습니다" />
          : (
            <>
              <div className="tbl-scroll">
                <table className="t">
                  <thead><tr><th>차량</th><th>위치</th><th>근거</th><th>연결</th><th>원격</th><th>정지</th><th>화물</th></tr></thead>
                  <tbody>
                    {props.vehicles.map(v => (
                      <tr key={v.id} onClick={() => setVehicleId(v.id)}
                        style={{ cursor: 'pointer', background: v.id === vehicleId ? 'var(--acc-soft)' : undefined }}>
                        <td className="mono">{v.code}</td>
                        <td className="mono">{v.nodeId === null ? '위치 불명' : nodeCode(props.map, v.nodeId)}</td>
                        <td><Chip tone={sourceTone(v.source)}>{sourceLabel(v.source)}</Chip></td>
                        <td><Chip tone={v.online ? 'ok' : 'id'}>{v.online ? '온라인' : '오프라인'}</Chip></td>
                        <td><Chip tone={v.remote ? 'ok' : 'id'}>{v.remote ? '원격' : '수동'}</Chip></td>
                        <td><Chip tone={v.idle ? 'id' : 'ac'}>{v.idle ? '정지' : '이동 중'}</Chip></td>
                        <td><Chip tone={v.carrierPresent ? 'ac' : 'id'}>{v.carrierPresent ? '있음' : '없음'}</Chip></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {selected && (
                <div style={{ border: '1px solid var(--ln2)', borderRadius: 6, padding: 12, display: 'grid', gap: 10 }}>
                  <b className="mono">{selected.code} 상태 보고</b>
                  <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 12 }}>
                    위치
                    <select value={draft.nodeId ?? ''} style={field}
                      onChange={e => setDraft(d => ({ ...d, nodeId: e.target.value === '' ? null : Number(e.target.value) }))}>
                      {/* Unknown must stay选택 가능: it is a real answer, not a missing input. */}
                      <option value="">위치 불명</option>
                      {reachable.map(n => <option key={n.id} value={n.id}>{n.code}</option>)}
                    </select>
                  </label>
                  <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', fontSize: 12 }}>
                    <Toggle label="온라인" v={draft.online} on={v => setDraft(d => ({ ...d, online: v }))} />
                    <Toggle label="원격 제어" v={draft.remote} on={v => setDraft(d => ({ ...d, remote: v }))} />
                    <Toggle label="정지 상태" v={draft.idle} on={v => setDraft(d => ({ ...d, idle: v }))} />
                    <Toggle label="화물 적재" v={draft.carrierPresent} on={v => setDraft(d => ({ ...d, carrierPresent: v }))} />
                  </div>
                  <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <button type="button" onClick={submit} disabled={busy || !hasWriteToken()} style={primary}>
                      {busy ? '보고 중…' : '보고'}
                    </button>
                    {!hasWriteToken() && <span className="hint">오른쪽 위 계정 메뉴에서 먼저 로그인해 주세요.</span>}
                  </div>
                  {error && <p className="hint" style={{ color: 'var(--crit)', margin: 0 }}>{error}</p>}
                  {result && <ReportOutcome result={result} map={props.map} />}
                </div>
              )}
            </>
          )}
      </div>
    </section>
  );
}

function ReportOutcome({ result, map }: { result: VehicleReportResult; map: MapData }) {
  const t = result.traffic;
  return (
    <div style={{ display: 'grid', gap: 6, fontSize: 12 }}>
      <span>
        기록됨 · 위치 <span className="mono">{result.vehicle.nodeId === null ? '불명' : nodeCode(map, result.vehicle.nodeId)}</span>
        {' '}<Chip tone={sourceTone(result.vehicle.source)}>{sourceLabel(result.vehicle.source)}</Chip>
      </span>
      {t && (t.authorized
        ? <span>점유 해제 <span className="mono">{t.released}</span>구간 · 잔여 보유 <span className="mono">{t.stillHeld}</span>구간</span>
        : <span style={{ color: 'var(--crit)' }}>
            허가받지 않은 노드를 보고했습니다. 점유 기록과 현장이 어긋나므로 아무것도 해제하지 않았습니다.
          </span>)}
    </div>
  );
}

function Toggle({ label, v, on }: { label: string; v: boolean; on: (v: boolean) => void }) {
  return (
    <label style={{ display: 'flex', gap: 5, alignItems: 'center', cursor: 'pointer' }}>
      <input type="checkbox" checked={v} onChange={e => on(e.target.checked)} />{label}
    </label>
  );
}

function nodeCode(map: MapData, id: number) { return map.nodes.find(n => n.id === id)?.code ?? String(id); }
function sourceLabel(s: string) {
  return s === 'DEVICE' ? '장비 보고' : s === 'SIMULATED' ? '모의 보고' : '설정값';
}
function sourceTone(s: string) { return s === 'DEVICE' ? 'ok' : s === 'SIMULATED' ? 'wr' : 'id'; }
function explainReport(code: string) {
  if (code === 'NODE_NOT_IN_REVISION') return '이 지도 revision에 없는 노드입니다. 위치가 아니라 잘못된 보고로 거부됐습니다.';
  if (code === 'VEHICLE_NOT_FOUND') return '이 지도 revision에 없는 차량입니다.';
  return code;
}

const field: React.CSSProperties = {
  border: '1px solid var(--ln2)', borderRadius: 5, padding: '5px 8px',
  background: 'var(--srf)', color: 'var(--ink)', fontSize: 12,
};
const primary: React.CSSProperties = {
  border: '1px solid var(--acc)', background: 'var(--acc)', color: '#fff',
  borderRadius: 5, padding: '6px 14px', fontSize: 12, cursor: 'pointer',
};

const ghost: React.CSSProperties = {
  border: '1px solid var(--ln2)', background: 'var(--srf)', color: 'var(--ink2)',
  borderRadius: 4, padding: '3px 10px', fontSize: 11, cursor: 'pointer',
};
