import React from 'react';
import {
  ApiError, cfgApi, get, send, ROLE_LABEL, ROLE_SCOPE,
  type AccountSettings, type AccountView, type NewAccount, type Role, type Session,
} from '../../api';
import { Chip, Empty } from '../../shell/States';
import { Grid, Row } from '../../shell/Panel';

const ROLES: Role[] = ['VIEWER', 'OPERATOR', 'MANAGER', 'ADMIN'];

/**
 * Who may log in, and what each may do.
 *
 * <p>A password is set here and never read back. The list says only whether one exists, because
 * that is all anybody needs from a listing and nothing else could leak by accident.
 */
export function AccountsScreen({ session }: { session: Session }) {
  const [accounts, setAccounts] = React.useState<AccountView[] | null>(null);
  const [notice, setNotice] = React.useState<{ tone: string; text: string } | null>(null);
  const [draft, setDraft] = React.useState<NewAccount>(
    { code: '', displayName: '', role: 'OPERATOR', enabled: true, password: '' });
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

  async function act(run: () => Promise<unknown>, done: string) {
    setBusy(true); setNotice(null);
    try { await run(); setReload(v => v + 1); setNotice({ tone: 'ok', text: done }); }
    catch (cause) { setNotice({ tone: 'er', text: cause instanceof ApiError ? explain(cause.message) : '처리하지 못했습니다.' }); }
    finally { setBusy(false); }
  }

  const create = () => act(
    () => send<AccountView>(cfgApi.accounts(), 'POST', draft, '계정 생성')
      .then(v => { setDraft({ code: '', displayName: '', role: 'OPERATOR', enabled: true, password: '' }); return v; }),
    '계정을 만들었습니다. 비밀번호는 본인에게 직접 전달하세요.');

  const change = (a: AccountView, patch: Partial<AccountSettings>) => act(
    () => send<AccountView>(`${cfgApi.accounts()}/${a.id}?expectedVersion=${a.version}`, 'PUT',
      { code: a.code, displayName: a.displayName, role: a.role, enabled: a.enabled, ...patch }, '계정 변경'),
    '변경했습니다. 이미 로그인해 있던 세션은 끝났습니다.');

  const resetPassword = (a: AccountView) => {
    const next = window.prompt(`${a.code}의 새 비밀번호를 입력하세요. 설정하면 지금 로그인해 있는 세션은 끝납니다.`);
    if (next === null || next.length < 4) return;
    act(() => send<AccountView>(`${cfgApi.accounts()}/${a.id}/password`, 'PUT', { password: next }, '비밀번호 변경'),
      '비밀번호를 바꿨습니다. 이전 비밀번호와 그 세션은 더 이상 쓸 수 없습니다.');
  };

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
          <span className="d">{mayChange ? '' : 'Lv4 관리자 권한이 필요합니다'}</span></span>
      </div>

      {notice && <div className="card"><div className="cb" style={{ padding: '10px 14px' }}>
        <p style={{ margin: 0, fontSize: 12 }}>
          <Chip tone={notice.tone}>{notice.tone === 'er' ? '거부' : '완료'}</Chip> {notice.text}
        </p>
      </div></div>}

      <section className="card">
        <div className="ch"><h2>계정</h2><span className="aux">비밀번호는 저장하지 않고 설정만 합니다</span></div>
        {accounts.length === 0
          ? <Empty title="등록된 계정이 없습니다" />
          : (
            <Grid cols="118px 1fr 112px 78px 86px 108px" head={['아이디', '이름', '등급', '상태', '로그인', '조작']}>
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
                  <span><Chip tone={a.canLogIn ? 'ok' : 'wr'}>{a.canLogIn ? '가능' : '비밀번호 없음'}</Chip></span>
                  <span>
                    <button type="button" disabled={!mayChange || busy} style={ghost}
                      onClick={() => resetPassword(a)}>비밀번호 변경</button>
                  </span>
                </Row>
              ))}
            </Grid>
          )}
        <p className="hint" style={{ padding: '9px 15px 14px', margin: 0 }}>
          등급을 내리거나 사용을 중지하면 그 계정으로 이미 열려 있던 로그인도 그 자리에서 끝납니다. 권한을
          거뒀는데 열려 있던 화면에서 계속 쓸 수 있으면 거둔 것이 아닙니다. 계정 변경은 유지보수 상태를
          요구하지 않습니다 — 계정을 급히 막아야 하는 순간은 대개 가동 중입니다.
        </p>
      </section>

      <section className="card">
        <div className="ch"><h2>계정 추가</h2><span className="aux">비밀번호는 만든 뒤 다시 볼 수 없습니다</span></div>
        <div className="cb" style={{ display: 'grid', gap: 9, maxWidth: 560 }}>
          <Field label="아이디">
            <input style={field} value={draft.code} disabled={!mayChange}
              onChange={e => setDraft(d => ({ ...d, code: e.target.value }))} placeholder="operator02" />
          </Field>
          <Field label="이름">
            <input style={field} value={draft.displayName} disabled={!mayChange}
              onChange={e => setDraft(d => ({ ...d, displayName: e.target.value }))} placeholder="야간 운전자" />
          </Field>
          <Field label="비밀번호">
            <input style={field} type="password" value={draft.password} disabled={!mayChange} autoComplete="new-password"
              onChange={e => setDraft(d => ({ ...d, password: e.target.value }))} />
          </Field>
          <Field label="등급">
            <div>
              <select style={field} value={draft.role} disabled={!mayChange}
                onChange={e => setDraft(d => ({ ...d, role: e.target.value as Role }))}>
                {ROLES.map(r => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
              </select>
              <div className="hint" style={{ marginTop: 3 }}>{ROLE_SCOPE[draft.role]}</div>
            </div>
          </Field>
          <div>
            <button type="button" style={primary} onClick={create}
              disabled={!mayChange || busy || !draft.code.trim() || !draft.displayName.trim() || draft.password.length < 4}>
              {busy ? '처리 중…' : '만들기'}
            </button>
          </div>
        </div>
      </section>
    </>
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
  if (code === 'ACCOUNT_CODE_IN_USE') return '같은 아이디의 계정이 이미 있습니다.';
  if (code === 'PASSWORD_TOO_SHORT') return '비밀번호가 너무 짧습니다.';
  if (code === 'UNKNOWN_ROLE') return '알 수 없는 등급입니다.';
  if (code === 'VERSION_CONFLICT') return '다른 곳에서 먼저 수정되었습니다. 새로 불러와 비교해 주세요.';
  if (code === 'ACCOUNT_NOT_FOUND') return '계정을 찾지 못했습니다.';
  if (code === 'ROLE_ADMIN_REQUIRED') return '계정 관리는 Lv4 관리자 권한이 필요합니다.';
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
