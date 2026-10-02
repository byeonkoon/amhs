import React from 'react';
import {
  ApiError, cfgApi, get, send, ROLE_LABEL,
  type AccountSettings, type AccountView, type IssuedAccount, type Role, type Session,
} from '../../api';
import { Chip, Empty } from '../../shell/States';
import { Grid, Row } from '../../shell/Panel';

const ROLES: Role[] = ['VIEWER', 'OPERATOR', 'ENGINEER', 'ADMIN'];
/** What each level may do, said once here so the screen and the gate cannot describe it differently. */
const ROLE_SCOPE: Record<Role, string> = {
  VIEWER: '조회만',
  OPERATOR: '배차·취소·허가 연장·차량 상태 보고',
  ENGINEER: '위 전부 + 차량·포트·설비 설정',
  ADMIN: '위 전부 + 노드·구간, 시스템 옵션, 계정',
};

/**
 * Accounts and what each may do.
 *
 * <p>A token is shown once, when it is issued, and never again — the list only says whether one
 * exists. That is why this screen keeps the issued value in a panel the operator must dismiss:
 * there is no second chance to read it, and no way to ask the server for it later.
 */
export function AccountsScreen({ session }: { session: Session }) {
  const [accounts, setAccounts] = React.useState<AccountView[] | null>(null);
  const [notice, setNotice] = React.useState<{ tone: string; text: string } | null>(null);
  const [issued, setIssued] = React.useState<IssuedAccount | null>(null);
  const [draft, setDraft] = React.useState<AccountSettings>({ code: '', displayName: '', role: 'OPERATOR', enabled: true });
  const [busy, setBusy] = React.useState(false);
  const [reload, setReload] = React.useState(0);

  const mayChange = session.allows.includes('accounts');

  React.useEffect(() => {
    const c = new AbortController();
    get<AccountView[]>(cfgApi.accounts(), c.signal)
      .then(setAccounts)
      .catch(cause => { if ((cause as Error)?.name !== 'AbortError') setAccounts([]); });
    return () => c.abort();
  }, [reload]);

  async function act<T>(run: () => Promise<T>, done: (v: T) => void) {
    setBusy(true); setNotice(null);
    try { done(await run()); setReload(v => v + 1); }
    catch (cause) { setNotice({ tone: 'er', text: cause instanceof ApiError ? explain(cause.message) : '처리하지 못했습니다.' }); }
    finally { setBusy(false); }
  }

  const create = () => act(
    () => send<IssuedAccount>(cfgApi.accounts(), 'POST', draft, '계정 생성'),
    v => { setIssued(v); setDraft({ code: '', displayName: '', role: 'OPERATOR', enabled: true }); });

  const change = (a: AccountView, patch: Partial<AccountSettings>) => act(
    () => send<AccountView>(`${cfgApi.accounts()}/${a.id}?expectedVersion=${a.version}`, 'PUT',
      { code: a.code, displayName: a.displayName, role: a.role, enabled: a.enabled, ...patch }, '계정 변경'),
    () => setNotice({ tone: 'ok', text: '변경했습니다.' }));

  const reissue = (a: AccountView) => act(
    () => send<IssuedAccount>(`${cfgApi.accounts()}/${a.id}/token`, 'POST', undefined, '토큰 재발급'),
    v => setIssued(v));

  const revoke = (a: AccountView) => act(
    () => send<AccountView>(`${cfgApi.accounts()}/${a.id}/token`, 'DELETE', undefined, '토큰 회수'),
    () => setNotice({ tone: 'ok', text: '토큰을 회수했습니다. 계정과 이력은 남습니다.' }));

  if (accounts === null) return <section className="card"><div className="cb"><p className="hint" style={{ margin: 0 }}>불러오는 중…</p></div></section>;

  const admins = accounts.filter(a => a.role === 'ADMIN' && a.enabled).length;

  return (
    <>
      <div className="statbar">
        <span className="s"><span className="k">계정</span><span className="v">{accounts.length}</span></span>
        <span className="s"><span className="k">사용 중</span><span className="v">{accounts.filter(a => a.enabled).length}</span></span>
        <span className="s"><span className="k">관리자</span>
          <span className="v" style={admins === 1 ? { color: 'var(--warn)' } : undefined}>{admins}</span>
          <span className="d">{admins === 1 ? '마지막 관리자는 내릴 수 없습니다' : ''}</span></span>
        <span className="s"><span className="k">변경</span><span className="v">{mayChange ? '가능' : '불가'}</span>
          <span className="d">{mayChange ? '' : '관리자 권한이 필요합니다'}</span></span>
      </div>

      {issued && <IssuedToken issued={issued} onDismiss={() => setIssued(null)} />}
      {notice && <div className="card"><div className="cb" style={{ padding: '10px 14px' }}>
        <p style={{ margin: 0, fontSize: 12 }}>
          <Chip tone={notice.tone}>{notice.tone === 'er' ? '거부' : '완료'}</Chip> {notice.text}
        </p>
      </div></div>}

      <section className="card">
        <div className="ch"><h2>계정</h2><span className="aux">토큰은 발급할 때 한 번만 보입니다</span></div>
        {accounts.length === 0
          ? <Empty title="등록된 계정이 없습니다" hint="계정을 만들면 토큰이 한 번 표시됩니다. 그 값은 다시 볼 수 없습니다." />
          : (
            <Grid cols="110px 1fr 92px 80px 86px 150px" head={['코드', '이름', '권한', '상태', '토큰', '조작']}>
              {accounts.map(a => (
                <Row key={a.id}>
                  <span className="mono">{a.code}</span>
                  <span>{a.displayName}</span>
                  <span>
                    <select value={a.role} disabled={!mayChange || busy} title={ROLE_SCOPE[a.role]}
                      onChange={e => change(a, { role: e.target.value as Role })} style={picker}>
                      {ROLES.map(r => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
                    </select>
                  </span>
                  <span>
                    <button type="button" disabled={!mayChange || busy} style={chipButton(a.enabled)}
                      onClick={() => change(a, { enabled: !a.enabled })}>
                      {a.enabled ? '사용' : '중지'}
                    </button>
                  </span>
                  <span><Chip tone={a.hasToken ? 'ok' : 'id'}>{a.hasToken ? '발급됨' : '없음'}</Chip></span>
                  <span style={{ display: 'flex', gap: 5 }}>
                    <button type="button" disabled={!mayChange || busy} style={ghost} onClick={() => reissue(a)}>재발급</button>
                    <button type="button" disabled={!mayChange || busy || !a.hasToken} style={ghost} onClick={() => revoke(a)}>회수</button>
                  </span>
                </Row>
              ))}
            </Grid>
          )}
        <p className="hint" style={{ padding: '9px 15px 14px', margin: 0 }}>
          회수는 계정을 지우지 않고 들어올 수단만 없앱니다. 지워 버리면 그 계정이 남긴 조작 이력에 주인이 사라집니다.
          계정 변경은 유지보수 상태를 요구하지 않습니다 — 토큰을 급히 막아야 하는 순간은 보통 가동 중입니다.
        </p>
      </section>

      <section className="card">
        <div className="ch"><h2>계정 추가</h2><span className="aux">토큰은 서버가 만들고, 저장은 해시만 합니다</span></div>
        <div className="cb" style={{ display: 'grid', gap: 9, maxWidth: 560 }}>
          <Field label="코드">
            <input style={field} value={draft.code} disabled={!mayChange}
              onChange={e => setDraft(d => ({ ...d, code: e.target.value }))} placeholder="operator01" />
          </Field>
          <Field label="이름">
            <input style={field} value={draft.displayName} disabled={!mayChange}
              onChange={e => setDraft(d => ({ ...d, displayName: e.target.value }))} placeholder="야간 운전자" />
          </Field>
          <Field label="권한">
            <div>
              <select style={field} value={draft.role} disabled={!mayChange}
                onChange={e => setDraft(d => ({ ...d, role: e.target.value as Role }))}>
                {ROLES.map(r => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
              </select>
              <div className="hint" style={{ marginTop: 3 }}>{ROLE_SCOPE[draft.role]}</div>
            </div>
          </Field>
          <div>
            <button type="button" style={primary} disabled={!mayChange || busy || !draft.code.trim() || !draft.displayName.trim()}
              onClick={create}>{busy ? '처리 중…' : '만들고 토큰 발급'}</button>
          </div>
        </div>
      </section>
    </>
  );
}

/** The only place a token is ever shown. It is not stored anywhere and cannot be asked for again. */
function IssuedToken({ issued, onDismiss }: { issued: IssuedAccount; onDismiss: () => void }) {
  const [copied, setCopied] = React.useState(false);
  return (
    <section className="card" style={{ borderColor: 'var(--warn)' }}>
      <div className="ch"><h2>{issued.account.code} 토큰</h2>
        <span className="aux">지금 전달하세요. 이 값은 다시 볼 수 없습니다</span></div>
      <div className="cb" style={{ display: 'grid', gap: 9 }}>
        <code className="mono" style={{
          background: 'var(--gnd)', border: '1px solid var(--ln2)', borderRadius: 5,
          padding: '9px 11px', fontSize: 12, wordBreak: 'break-all',
        }}>{issued.token}</code>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <button type="button" style={ghost}
            onClick={() => { navigator.clipboard?.writeText(issued.token).then(() => setCopied(true)).catch(() => setCopied(false)); }}>
            복사
          </button>
          <button type="button" style={primary} onClick={onDismiss}>전달했습니다</button>
          {copied && <span className="hint">복사했습니다.</span>}
        </div>
        <p className="hint" style={{ margin: 0 }}>
          서버는 이 값의 해시만 보관합니다. 잃어버리면 다시 찾을 수 없고 재발급만 가능하며, 재발급하면 이전
          토큰은 즉시 막힙니다.
        </p>
      </div>
    </section>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '80px 1fr', gap: 10, alignItems: 'start' }}>
      <label style={{ fontSize: 12, fontWeight: 500, paddingTop: 4 }}>{label}</label>
      <div>{children}</div>
    </div>
  );
}

function explain(code: string): string {
  if (code === 'LAST_ADMIN') return '마지막 관리자입니다. 내리면 권한을 되돌려 줄 사람이 남지 않으므로 거부했습니다. 다른 관리자를 먼저 두세요.';
  if (code === 'ACCOUNT_CODE_IN_USE') return '같은 코드의 계정이 이미 있습니다.';
  if (code === 'UNKNOWN_ROLE') return '알 수 없는 권한입니다.';
  if (code === 'VERSION_CONFLICT') return '다른 곳에서 먼저 수정되었습니다. 새로 불러와 비교해 주세요.';
  if (code === 'ACCOUNT_NOT_FOUND') return '계정을 찾지 못했습니다.';
  if (code === 'ROLE_ADMIN_REQUIRED') return '계정 관리는 관리자 권한이 필요합니다.';
  return code;
}

const field: React.CSSProperties = {
  border: '1px solid var(--ln2)', borderRadius: 5, padding: '5px 8px',
  background: 'var(--srf)', color: 'var(--ink)', fontSize: 12, minWidth: 180,
};
const picker: React.CSSProperties = { ...field, minWidth: 0, width: '100%', fontSize: 11, padding: '2px 4px' };
const primary: React.CSSProperties = {
  border: '1px solid var(--acc)', background: 'var(--acc)', color: '#fff',
  borderRadius: 5, padding: '6px 16px', fontSize: 12, cursor: 'pointer',
};
const ghost: React.CSSProperties = {
  border: '1px solid var(--ln2)', background: 'var(--srf)', color: 'var(--ink2)',
  borderRadius: 4, padding: '2px 9px', fontSize: 10.5, cursor: 'pointer',
};
function chipButton(on: boolean): React.CSSProperties {
  return {
    border: `1px solid ${on ? 'var(--ln2)' : 'var(--warn)'}`,
    background: on ? 'var(--srf)' : '#FBF0DA',
    color: on ? 'var(--ink2)' : '#8A5C05',
    borderRadius: 4, padding: '2px 8px', fontSize: 10.5, cursor: 'pointer', width: '100%',
  };
}
