import React from 'react';
import {
  ApiError, cfgApi, get, send, setWriteToken, hasWriteToken,
  ROLE_LABEL, type Cfg, type OperationOptions, type Session,
} from '../../api';
import { Chip, Empty } from '../../shell/States';
import { Panel } from '../../shell/Panel';
import { explainCode } from '../configuration/rules';

/**
 * Site-wide options. These apply to every vehicle and every command, which is why they are here
 * and not in a machine's screen, and why they need the highest authority.
 *
 * <p>Each field says what it changes. An option that only described an intention would read the
 * same as one that does something, and an operator could not tell which they were looking at.
 */
export function SystemOptionsScreen({ session }: { session: Session }) {
  const [record, setRecord] = React.useState<Cfg<OperationOptions> | null>(null);
  const [draft, setDraft] = React.useState<OperationOptions | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [saved, setSaved] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);

  const load = React.useCallback(() => {
    const controller = new AbortController();
    get<Cfg<OperationOptions>>(cfgApi.system(), controller.signal)
      .then(r => { setRecord(r); setDraft(r.settings); setError(null); })
      .catch(cause => { if ((cause as Error).name !== 'AbortError') setError(describe(cause)); });
    return () => controller.abort();
  }, []);
  React.useEffect(load, [load]);

  const mayChange = session.allows.includes('system');
  const set = (patch: Partial<OperationOptions>) => setDraft(d => d && ({ ...d, ...patch }));

  async function save() {
    if (!record || !draft) return;
    setBusy(true); setError(null); setSaved(null);
    try {
      const next = await send<Cfg<OperationOptions>>(
        `${cfgApi.system()}?expectedVersion=${record.version}`, 'PUT', draft, '시스템 옵션 변경');
      setRecord(next); setDraft(next.settings);
      setSaved(`저장했습니다 (버전 ${next.version}).`);
    } catch (cause) {
      setError(describe(cause));
    } finally { setBusy(false); }
  }

  if (error && !draft) return <section className="card"><Empty title="시스템 옵션을 불러오지 못했습니다" hint={error} /></section>;
  if (!draft || !record) return <section className="card"><div className="cb"><p className="hint" style={{ margin: 0 }}>불러오는 중…</p></div></section>;

  return (
    <>
      <div className="statbar">
        <span className="s"><span className="k">권한</span><span className="v">{ROLE_LABEL[session.role]}</span></span>
        <span className="s"><span className="k">버전</span><span className="v">{record.version}</span></span>
        <span className="s"><span className="k">변경</span>
          <span className="v">{mayChange ? '가능' : '불가'}</span>
          <span className="d">{mayChange ? '' : '관리자 권한이 필요합니다'}</span></span>
      </div>

      {(error || saved) && (
        <div className="card"><div className="cb" style={{ padding: '10px 14px' }}>
          <p style={{ margin: 0, fontSize: 12 }}>
            <Chip tone={error ? 'er' : 'ok'}>{error ? '거부' : '완료'}</Chip> {error ?? saved}
          </p>
        </div></div>
      )}

      <Panel id="sys-comm" title="통신·프로토콜" aux="사이트의 모든 차량에 적용됩니다" defaultOpen padded>
        <Field label="통신 방식" note="차량마다 다르게 둘 수 없습니다. MQTT일 때만 VDA 프로파일을 씁니다.">
          <select style={field} value={draft.commType} disabled={!mayChange}
            onChange={e => set({ commType: e.target.value as OperationOptions['commType'] })}>
            {['PC', 'PLC', 'MQTT'].map(v => <option key={v} value={v}>{v}</option>)}
          </select>
        </Field>
      </Panel>

      <Panel id="sys-authority" title="이동 허가" aux="한 번의 허가가 덮는 범위와 연장 시점" defaultOpen padded>
        <Field label="허가 노드 수" note="차량이 선 곳부터 이 수를 넘지 않는 연속 구간만 허가합니다. 1~100.">
          <input type="number" style={field} value={draft.nodeCount} min={1} max={100} disabled={!mayChange}
            onChange={e => set({ nodeCount: Number(e.target.value) })} />
        </Field>
        <Field label="연장 사용" note="끄면 차량은 허가 끝에서 서고 새 이동 명령을 기다립니다.">
          <input type="checkbox" checked={draft.moreEnabled} disabled={!mayChange}
            onChange={e => set({ moreEnabled: e.target.checked })} />
        </Field>
        <Field label="연장 판정" note="NODE는 남은 허가 노드 수로, DISTANCE는 한 번의 완전한 허가 거리 대비 비율로 판단합니다.">
          <select style={field} value={draft.moreType} disabled={!mayChange}
            onChange={e => set({ moreType: e.target.value as OperationOptions['moreType'] })}>
            {['NODE', 'DISTANCE'].map(v => <option key={v} value={v}>{v}</option>)}
          </select>
        </Field>
        <Field label="남은 노드 수" note="허가 노드 수보다 작아야 합니다. 같거나 크면 허가 직후 바로 연장이 나갑니다.">
          <input type="number" style={field} value={draft.moreRemainingNodes} min={1} max={99} disabled={!mayChange}
            onChange={e => set({ moreRemainingNodes: Number(e.target.value) })} />
        </Field>
        <Field label="남은 거리 비율 (%)" note="DISTANCE 판정에서만 씁니다. 1~99.">
          <input type="number" style={field} value={draft.moreRemainingPercent} min={1} max={99} disabled={!mayChange}
            onChange={e => set({ moreRemainingPercent: Number(e.target.value) })} />
        </Field>
      </Panel>

      <Panel id="sys-dispatch" title="배차" aux="실행 스윕이 대기 작업을 맡을지" padded>
        <Field label="자동 배차"
          note="켜면 실행 스윕이 대기 중인 작업을 배차합니다. 스윕은 타이머가 아니라 호출로 돕니다.">
          <input type="checkbox" checked={draft.autoDispatch} disabled={!mayChange}
            onChange={e => set({ autoDispatch: e.target.checked })} />
        </Field>
      </Panel>

      <Panel id="sys-command" title="장비 명령" aux="도달하지 못한 것이 확인된 명령만 재시도합니다" padded>
        <Field label="재시도 상한"
          note="이 횟수를 넘기면 자동 재시도를 멈춥니다. 전달됐거나 결과가 불명한 명령은 애초에 재시도하지 않습니다.">
          <input type="number" style={field} value={draft.commandMaxAttempts} min={1} max={20} disabled={!mayChange}
            onChange={e => set({ commandMaxAttempts: Number(e.target.value) })} />
        </Field>
        <Field label="재시도 간격 (초)" note="다음 발행 때 즉시 반영됩니다. 서버를 다시 띄울 필요가 없습니다.">
          <input type="number" style={field} value={draft.commandRetrySeconds} min={1} max={3600} disabled={!mayChange}
            onChange={e => set({ commandRetrySeconds: Number(e.target.value) })} />
        </Field>
      </Panel>

      <Panel id="sys-display" title="표시" aux="언제부터 실시간이 아니라고 볼지" padded>
        <Field label="위치 보고 신선도 기준 (초)"
          note="마지막 보고가 이 시간보다 오래되면 실시간 수신이 아니라 수신 지연으로 표시합니다.">
          <input type="number" style={field} value={draft.positionStaleSeconds} min={1} max={3600} disabled={!mayChange}
            onChange={e => set({ positionStaleSeconds: Number(e.target.value) })} />
        </Field>
      </Panel>

      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <button type="button" style={primary} disabled={!mayChange || busy} onClick={save}>
          {busy ? '저장 중…' : '저장'}
        </button>
        <button type="button" style={ghost} disabled={busy} onClick={() => { setDraft(record.settings); setError(null); setSaved(null); }}>
          되돌리기
        </button>
        {!mayChange && <span className="hint">조회만 가능합니다. 변경하려면 관리자 토큰이 필요합니다.</span>}
        {mayChange && <span className="hint">설정 변경은 사이트가 유지보수 상태일 때만 받아들여집니다.</span>}
      </div>
    </>
  );
}

/** Token entry. The value is held in memory for the session and never stored or shown back. */
export function SignInScreen({ onChanged }: { onChanged: () => void }) {
  const [value, setValue] = React.useState('');
  const present = hasWriteToken();
  return (
    <section className="card">
      <div className="ch"><h2>접속 토큰</h2><span className="aux">조회는 토큰 없이 됩니다</span></div>
      <div className="cb" style={{ display: 'grid', gap: 10, maxWidth: 520 }}>
        <p className="hint" style={{ margin: 0 }}>
          상태를 바꾸는 요청만 토큰을 요구합니다. 입력한 값은 이 브라우저 세션의 메모리에만 두며 저장하거나 다시
          보여 주지 않습니다. 권한은 토큰에 딸린 계정이 정합니다.
        </p>
        <input type="password" style={{ ...field, width: '100%' }} value={value} placeholder={present ? '새 토큰을 입력하면 교체됩니다' : '토큰'}
          onChange={e => setValue(e.target.value)} autoComplete="off" />
        <div style={{ display: 'flex', gap: 8 }}>
          <button type="button" style={primary} disabled={!value.trim()}
            onClick={() => { setWriteToken(value); setValue(''); onChanged(); }}>적용</button>
          <button type="button" style={ghost} disabled={!present}
            onClick={() => { setWriteToken(null); setValue(''); onChanged(); }}>해제</button>
        </div>
        <p className="hint" style={{ margin: 0 }}>
          현재 상태: <b>{present ? '토큰 있음' : '토큰 없음'}</b>
        </p>
      </div>
    </section>
  );
}

function Field({ label, note, children }: { label: string; note: string; children: React.ReactNode }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '176px 1fr', gap: 10, alignItems: 'start', padding: '7px 0' }}>
      <label style={{ fontSize: 12, fontWeight: 500, paddingTop: 4 }}>{label}</label>
      <div>
        {children}
        <div className="hint" style={{ marginTop: 3 }}>{note}</div>
      </div>
    </div>
  );
}

function describe(cause: unknown): string {
  if (cause instanceof ApiError) return explainCode(cause.message);
  return cause instanceof Error ? cause.message : '알 수 없는 오류입니다.';
}

const field: React.CSSProperties = {
  border: '1px solid var(--ln2)', borderRadius: 5, padding: '5px 8px',
  background: 'var(--srf)', color: 'var(--ink)', fontSize: 12, minWidth: 120,
};
const primary: React.CSSProperties = {
  border: '1px solid var(--acc)', background: 'var(--acc)', color: '#fff',
  borderRadius: 5, padding: '6px 16px', fontSize: 12, cursor: 'pointer',
};
const ghost: React.CSSProperties = {
  border: '1px solid var(--ln2)', background: 'var(--srf)', color: 'var(--ink2)',
  borderRadius: 5, padding: '6px 14px', fontSize: 12, cursor: 'pointer',
};
