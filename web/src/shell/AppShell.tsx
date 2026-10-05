import React from 'react';
import { ROLE_LABEL, ROLE_LEVEL, type Role, type Session } from '../api';

/** Menu entries share one shape so every screen shows the same sidebar. */
export type MenuKey = 'overview' | 'fleet' | 'resources' | 'equipment' | 'transport' | 'traffic'
  | 'inventory' | 'alarms' | 'history' | 'diagnostics';
/** `needs` is the lowest level that may open the screen at all. */
type Entry = { key: MenuKey; label: string; sub: string; ready: boolean; needs: Role };

/**
 * Resources holds what the controller commands — vehicles, the nodes and segments they run on,
 * and the ports that face equipment. Equipment holds the equipment itself, with its racks and
 * slots. They are separate because configuring a machine and rewiring the track are different
 * jobs with different consequences.
 *
 * <p>The level each screen needs is the level its screen can act at: watching the floor is level 1,
 * running work and giving way is level 2, what is being carried and stored is level 3, and changing
 * the vehicles, the track, the equipment or who may log in is level 4.
 */
const MENU: Entry[] = [
  { key: 'overview', label: 'Overview', sub: 'Status / KPI', ready: true, needs: 'VIEWER' },
  { key: 'fleet', label: 'Fleet', sub: 'Map / Vehicles', ready: true, needs: 'VIEWER' },
  { key: 'transport', label: 'Transport', sub: 'Job / Route', ready: true, needs: 'OPERATOR' },
  { key: 'traffic', label: 'Traffic', sub: 'Reservation / Deadlock', ready: true, needs: 'OPERATOR' },
  { key: 'resources', label: 'Resources', sub: 'Vehicle / Node / Port', ready: true, needs: 'ADMIN' },
  { key: 'equipment', label: 'Equipment', sub: 'EQP / Rack / Slot', ready: true, needs: 'OPERATOR' },
  { key: 'inventory', label: 'Inventory', sub: 'Carrier / Stocker', ready: true, needs: 'MANAGER' },
  { key: 'alarms', label: 'Alarms', sub: 'Event / Notification', ready: true, needs: 'VIEWER' },
  { key: 'history', label: 'History', sub: 'Log / Trace', ready: true, needs: 'VIEWER' },
  { key: 'diagnostics', label: 'Diagnostics', sub: 'Health / Recovery', ready: true, needs: 'VIEWER' },
];

/** The lowest level that may open a screen, so main and the shell cannot disagree about it. */
export function menuNeeds(key: MenuKey): Role {
  return MENU.find(e => e.key === key)?.needs ?? 'ADMIN';
}
/** Whether this session may open that screen. */
export function mayOpen(session: Session, key: MenuKey): boolean {
  return session.level >= ROLE_LEVEL[menuNeeds(key)];
}

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
  /**
   * How many conditions are true now, so the count is visible from every screen. `unseen` is how
   * many nobody has said they have looked at: the badge shows that, because a fault somebody is
   * already standing in front of should stop shouting without being made to look fixed.
   */
  alarms?: { critical: number; warning: number; unseen: number };
  /** The site's run mode as stored, so the frame never claims simulation for a site on real devices. */
  runMode?: 'SIMULATION' | 'OPERATION' | null;
  /**
   * Whether the fleet is running. Null until the server has said. It rides on the frame because a
   * stopped plant must be visible from whatever screen somebody happens to be looking at -- a
   * person who cannot see that the site is stopped reads every other number on the screen wrong.
   */
  running?: boolean | null;
  /** Stops the fleet, or starts it again. Absent when this session may not. */
  onRunning?: (running: boolean) => void;
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
          {MENU.map(entry => {
            // A level that is not enough is shown with the level it takes. Hiding the entry would
            // teach that the screen does not exist, and somebody would go looking for it.
            const allowed = props.session.level >= ROLE_LEVEL[entry.needs];
            const open = entry.ready && allowed;
            return (
              <li key={entry.key} className={entry.key === props.active ? 'on' : undefined}>
                <button
                  type="button"
                  disabled={!open}
                  aria-current={entry.key === props.active ? 'page' : undefined}
                  title={!entry.ready ? '아직 구현하지 않은 화면입니다.'
                    : allowed ? undefined : `${ROLE_LABEL[entry.needs]} 이상 권한이 필요합니다. 계정 메뉴에서 로그인하세요.`}
                  onClick={() => open && props.onSelect(entry.key)}>
                  <span className="tx">
                    <span className="t1">{entry.label}</span>
                    <span className="t2">{entry.sub}</span>
                  </span>
                  {entry.ready && !allowed && <span className="need">Lv{ROLE_LEVEL[entry.needs]}</span>}
                  {/* A count on the entry, so a fault is visible from whatever screen is open. */}
                  {entry.key === 'alarms' && open && props.alarms && props.alarms.unseen > 0 && (
                    <span className={`count${props.alarms.critical ? ' crit' : ''}`}
                      title={`미확인 ${props.alarms.unseen} · 심각 ${props.alarms.critical} · 주의 ${props.alarms.warning}`}>
                      {props.alarms.unseen}
                    </span>
                  )}
                </button>
              </li>
            );
          })}
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
          {/* Stopped is said where it cannot be missed, and in the same place every time. */}
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginLeft: 'auto' }}>
            {props.running === false && (
              <span style={{
                border: '1px solid var(--crit)', background: '#FBEAE7', color: '#8E2B23',
                borderRadius: 5, padding: '4px 11px', fontSize: 12, fontWeight: 600, whiteSpace: 'nowrap',
              }}>정지 — 작업이 진행되지 않습니다</span>
            )}
            {props.onRunning && props.running !== null && props.running !== undefined && (
              <button type="button" onClick={() => props.onRunning?.(!props.running)}
                title={props.running
                  ? '작업 진행을 멈춥니다. 작업 접수는 계속 되고, 배차·이동·명령 전송과 시뮬레이션 주행이 멈춥니다.'
                  : '작업 진행을 다시 시작합니다.'}
                style={{
                  border: `1px solid ${props.running ? 'var(--crit)' : 'var(--good)'}`,
                  background: props.running ? 'var(--srf)' : 'var(--good)',
                  color: props.running ? 'var(--crit)' : '#fff',
                  borderRadius: 5, padding: '4px 14px', fontSize: 12, fontWeight: 600, cursor: 'pointer',
                  whiteSpace: 'nowrap',
                }}>{props.running ? '정지' : '활성'}</button>
            )}
            <div className="simbadge">{props.runMode === 'SIMULATION' ? '시뮬레이션 운전 중' : props.runMode === 'OPERATION' ? '실제 장비 운전' : '운전 모드 확인 중'}</div>
          </div>
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
            {session.authenticated ? `${ROLE_LABEL[session.role]} · ${session.role}` : '로그인하지 않음 · 조회만 가능합니다'}
          </div>
          <div className="sep" />
          <button type="button" role="menuitem" disabled={!may('system')} onClick={() => pick('system')}>
            시스템 옵션<span className="lv">Lv4</span>
          </button>
          <button type="button" role="menuitem" disabled={!may('accounts')} onClick={() => pick('accounts')}>
            계정·권한<span className="lv">Lv4</span>
          </button>
          <div className="sep" />
          <button type="button" role="menuitem" onClick={() => pick('signin')}>
            {session.authenticated ? '로그인 정보·로그아웃' : '로그인'}
          </button>
        </div>
      )}
    </div>
  );
}
