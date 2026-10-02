import React from 'react';
import {
  ApiError, opsApi, post, setWriteToken, hasWriteToken,
  ROLE_LABEL, ROLE_SCOPE, type LoggedIn, type Session,
} from '../../api';
import { Chip } from '../../shell/States';

/**
 * Logging in.
 *
 * <p>The session the server hands back is held in this browser tab's memory for as long as the tab
 * is open. It is not written to storage: a value on disk outlives the person sitting here, and
 * nothing about a shift handover should carry someone else's authority with it.
 */
export function SignInScreen({ session, onChanged }: { session: Session; onChanged: () => void }) {
  const [code, setCode] = React.useState('');
  const [password, setPassword] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [until, setUntil] = React.useState<string | null>(null);

  async function logIn(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      const res = await post<LoggedIn>(opsApi.logIn(), { code: code.trim(), password });
      setWriteToken(res.token);
      setPassword('');
      setUntil(new Date(res.expiresAt).toLocaleString('ko-KR', { hour12: false }));
      onChanged();
    } catch (cause) {
      setError(cause instanceof ApiError ? explain(cause) : '로그인하지 못했습니다.');
    } finally { setBusy(false); }
  }

  async function logOut() {
    setBusy(true);
    try { await post(opsApi.logOut(), undefined, 'DELETE'); } catch { /* the session ends here either way */ }
    setWriteToken(null); setUntil(null); setCode(''); setPassword('');
    onChanged();
    setBusy(false);
  }

  if (session.authenticated) return (
    <section className="card">
      <div className="ch"><h2>로그인 상태</h2></div>
      <div className="cb" style={{ display: 'grid', gap: 10, maxWidth: 520 }}>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <b className="mono">{session.code}</b>
          <Chip tone="ok">{ROLE_LABEL[session.role]}</Chip>
        </div>
        <p className="hint" style={{ margin: 0 }}>{ROLE_SCOPE[session.role]}</p>
        {until && <p className="hint" style={{ margin: 0 }}>이 로그인은 <b>{until}</b>까지 유효합니다.</p>}
        <div><button type="button" style={ghost} disabled={busy} onClick={logOut}>로그아웃</button></div>
        <p className="hint" style={{ margin: 0 }}>
          로그인은 이 탭의 메모리에만 있습니다. 저장하지 않으므로 탭을 닫으면 끝나고, 다른 사람이 같은 화면을
          이어받아도 이 권한이 따라가지 않습니다.
        </p>
      </div>
    </section>
  );

  return (
    <section className="card">
      <div className="ch"><h2>로그인</h2><span className="aux">조회는 로그인 없이도 됩니다</span></div>
      <form className="cb" onSubmit={logIn} style={{ display: 'grid', gap: 10, maxWidth: 420 }}>
        <label style={{ display: 'grid', gap: 4, fontSize: 12 }}>
          아이디
          <input style={field} value={code} autoComplete="username" autoFocus
            onChange={e => setCode(e.target.value)} />
        </label>
        <label style={{ display: 'grid', gap: 4, fontSize: 12 }}>
          비밀번호
          <input style={field} type="password" value={password} autoComplete="current-password"
            onChange={e => setPassword(e.target.value)} />
        </label>
        {error && <p style={{ margin: 0, fontSize: 12 }}><Chip tone="er">거부</Chip> {error}</p>}
        <div>
          <button type="submit" style={primary} disabled={busy || !code.trim() || !password}>
            {busy ? '확인 중…' : '로그인'}
          </button>
        </div>
        <p className="hint" style={{ margin: 0 }}>
          화면을 보는 것은 누구나 할 수 있고, 무엇을 바꾸거나 움직일 수 있는지는 계정의 등급이 정합니다.
          {hasWriteToken() ? '' : ' 지금은 조회만 가능합니다.'}
        </p>
      </form>
    </section>
  );
}

function explain(error: ApiError): string {
  if (error.status === 401) return '아이디나 비밀번호가 맞지 않습니다.';
  if (error.status === 0) return '서버에 연결하지 못했습니다.';
  return error.message;
}

const field: React.CSSProperties = {
  border: '1px solid var(--ln2)', borderRadius: 5, padding: '6px 9px',
  background: 'var(--srf)', color: 'var(--ink)', fontSize: 13,
};
const primary: React.CSSProperties = {
  border: '1px solid var(--acc)', background: 'var(--acc)', color: '#fff',
  borderRadius: 5, padding: '7px 18px', fontSize: 12.5, cursor: 'pointer',
};
const ghost: React.CSSProperties = {
  border: '1px solid var(--ln2)', background: 'var(--srf)', color: 'var(--ink2)',
  borderRadius: 5, padding: '6px 14px', fontSize: 12, cursor: 'pointer',
};
