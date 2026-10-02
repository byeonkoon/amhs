import React from 'react';
import { ROLE_LABEL, type Session } from '../api';

/** Menu entries share one shape so every screen shows the same sidebar. */
export type MenuKey = 'overview' | 'fleet' | 'resources' | 'equipment' | 'transport' | 'traffic'
  | 'inventory' | 'alarms' | 'history' | 'diagnostics';
type Entry = { key: MenuKey; label: string; sub: string; ready: boolean };

/**
 * Resources holds what the controller commands — vehicles, the nodes and segments they run on,
 * and the ports that face equipment. Equipment holds the equipment itself, with its racks and
 * slots. They are separate because configuring a machine and rewiring the track are different
 * jobs with different consequences.
 */
const MENU: Entry[] = [
  { key: 'overview', label: 'Overview', sub: 'Status / KPI', ready: false },
  { key: 'fleet', label: 'Fleet', sub: 'Map / Vehicles', ready: true },
  { key: 'transport', label: 'Transport', sub: 'Job / Route', ready: true },
  { key: 'traffic', label: 'Traffic', sub: 'Reservation / Deadlock', ready: true },
  { key: 'resources', label: 'Resources', sub: 'Vehicle / Node / Port', ready: true },
  { key: 'equipment', label: 'Equipment', sub: 'EQP / Rack / Slot', ready: true },
  { key: 'inventory', label: 'Inventory', sub: 'Carrier / Stocker', ready: false },
  { key: 'alarms', label: 'Alarms', sub: 'Event / Notification', ready: false },
  { key: 'history', label: 'History', sub: 'Log / Trace', ready: true },
  { key: 'diagnostics', label: 'Diagnostics', sub: 'Health / Recovery', ready: true },
];

export type ServerState = 'connecting' | 'connected' | 'unreachable';

/** Server time shown in one place so screens never render their own clock format. */
function useClock(): string {
  const [now, setNow] = React.useState(() => new Date());
  React.useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const week = ['일', '월', '화', '수', '목', '금', '토'][now.getDay()];
  const p = (v: number) => String(v).padStart(2, '0');
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())} (${week}) ${p(now.getHours())}:${p(now.getMinutes())}:${p(now.getSeconds())}`;
}

export function AppShell(props: {
  active: MenuKey;
  onSelect: (key: MenuKey) => void;
  crumb: string[];
  title: string;
  subtitle: string;
  server: ServerState;
  session: Session;
  /** The site's run mode as stored, so the frame never claims simulation for a site on real devices. */
  runMode?: 'SIMULATION' | 'OPERATION' | null;
  onAccount: (item: 'system' | 'accounts' | 'signin') => void;
  children: React.ReactNode;
}) {
  const clock = useClock();
  const serverDot = props.server === 'connected' ? 'ok' : props.server === 'connecting' ? 'wr' : 'er';
  const serverText = props.server === 'connected' ? 'Connected' : props.server === 'connecting' ? 'Connecting' : 'Unreachable';
  return (
    <div className="shell compact">
      <aside className="nav">
        <div className="nav-brand">
          <b>AMHS Operations</b>
          <span>Automated Material Handling System</span>
        </div>
        <ul className="nav-menu">
          {MENU.map(entry => (
            <li key={entry.key} className={entry.key === props.active ? 'on' : undefined}>
              <button
                type="button"
                disabled={!entry.ready}
                aria-current={entry.key === props.active ? 'page' : undefined}
                title={entry.ready ? undefined : '아직 구현하지 않은 화면입니다.'}
                onClick={() => entry.ready && props.onSelect(entry.key)}>
                <span className="tx">
                  <span className="t1">{entry.label}</span>
                  <span className="t2">{entry.sub}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
        <div className="nav-foot">
          <div className="row">
            <span>Server</span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <i className={`dot ${serverDot}`} />
              <span className="mono">{serverText}</span>
            </span>
          </div>
          <div className="row"><span>Build</span><span className="mono">v0.1.0 · {props.runMode === 'SIMULATION' ? 'SIM' : props.runMode === 'OPERATION' ? 'OPR' : '—'}</span></div>
        </div>
      </aside>

      <div className="main">
        <div className="topbar">
          <nav className="crumb" aria-label="위치">
            {props.crumb.map((part, i) => (
              <React.Fragment key={part}>
                {i > 0 && <span className="sep">›</span>}
                <span className={i === props.crumb.length - 1 ? 'cur' : undefined}>{part}</span>
              </React.Fragment>
            ))}
          </nav>
          <div className="top-right">
            <span className="clock">{clock}</span>
            <span className="sysok"><i className={`dot ${serverDot}`} />{props.server === 'connected' ? '시스템 정상' : props.server === 'connecting' ? '연결 확인 중' : '서버 미연결'}</span>
            <AccountMenu session={props.session} onSelect={props.onAccount} />
          </div>
        </div>

        <div className="phead">
          <div>
            <h1>{props.title}</h1>
            <div className="sub">{props.subtitle}</div>
          </div>
          <div className="simbadge">{props.runMode === 'SIMULATION' ? '시뮬레이션 운전 중' : props.runMode === 'OPERATION' ? '실제 장비 운전' : '운전 모드 확인 중'}</div>
        </div>

        <div className="body">{props.children}</div>

        <div className="appfoot">
          <span>AMHS Operations · <span className="mono">v0.1.0</span></span>
          <span className="r"><span>관제시스템</span><span className="mono">{props.runMode === 'SIMULATION' ? '가상 차량 · 시뮬레이션 모드' : props.runMode === 'OPERATION' ? '실제 장비 · 오퍼레이션 모드' : '—'}</span></span>
        </div>
      </div>
    </div>
  );
}

/**
 * Site-wide settings live behind the account, not in a machine's screen, because they are a
 * property of who you are signed in as and what that lets you change. Entries the session may
 * not use are shown disabled with the level they need, so the limit is visible rather than
 * discovered by being refused.
 */
function AccountMenu({ session, onSelect }: { session: Session; onSelect: (item: 'system' | 'accounts' | 'signin') => void }) {
  const [open, setOpen] = React.useState(false);
  const box = React.useRef<HTMLDivElement>(null);
  React.useEffect(() => {
    if (!open) return;
    const away = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false); };
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', away); document.addEventListener('keydown', esc);
    return () => { document.removeEventListener('mousedown', away); document.removeEventListener('keydown', esc); };
  }, [open]);

  const may = (area: string) => session.allows.includes(area);
  const pick = (item: 'system' | 'accounts' | 'signin') => { setOpen(false); onSelect(item); };
  const initials = session.code.slice(0, 2).toUpperCase();

  return (
    <div className="acct" ref={box}>
      <button type="button" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(v => !v)}>
        <span className="avatar">{initials}</span>
        <span className="mono" style={{ fontSize: 12 }}>{session.code}</span>
        <span style={{ fontSize: 10, color: 'var(--mut)' }}>{ROLE_LABEL[session.role]}</span>
      </button>
      {open && (
        <div className="acct-menu" role="menu">
          <div className="who">
            <b className="mono">{session.code}</b><br />
            {session.authenticated ? `권한 ${ROLE_LABEL[session.role]} · ${session.role}` : '토큰 없음 · 조회만 가능합니다'}
          </div>
          <div className="sep" />
          <button type="button" role="menuitem" disabled={!may('system')} onClick={() => pick('system')}>
            시스템 옵션<span className="lv">ADMIN</span>
          </button>
          <button type="button" role="menuitem" disabled={!may('accounts')} onClick={() => pick('accounts')}>
            계정·권한<span className="lv">ADMIN</span>
          </button>
          <div className="sep" />
          <button type="button" role="menuitem" onClick={() => pick('signin')}>
            {session.authenticated ? '토큰 변경·해제' : '토큰 입력'}
          </button>
        </div>
      )}
    </div>
  );
}
