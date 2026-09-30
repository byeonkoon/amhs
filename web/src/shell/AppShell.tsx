import React from 'react';

/** Menu entries share one shape so every screen shows the same sidebar. */
export type MenuKey = 'overview' | 'fleet' | 'equipment' | 'transport' | 'traffic' | 'inventory' | 'alarms' | 'history' | 'diagnostics';
type Entry = { key: MenuKey; label: string; sub: string; ready: boolean };

const MENU: Entry[] = [
  { key: 'overview', label: 'Overview', sub: 'Status / KPI', ready: false },
  { key: 'fleet', label: 'Fleet', sub: 'OHT / AGV', ready: true },
  { key: 'equipment', label: 'Equipment', sub: 'Stations / Ports', ready: true },
  { key: 'transport', label: 'Transport', sub: 'Job / Route', ready: true },
  { key: 'traffic', label: 'Traffic', sub: 'Reservation / Deadlock', ready: true },
  { key: 'inventory', label: 'Inventory', sub: 'Carrier / Stocker', ready: false },
  { key: 'alarms', label: 'Alarms', sub: 'Event / Notification', ready: false },
  { key: 'history', label: 'History', sub: 'Log / Trace', ready: false },
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
  children: React.ReactNode;
}) {
  const clock = useClock();
  const serverDot = props.server === 'connected' ? 'ok' : props.server === 'connecting' ? 'wr' : 'er';
  const serverText = props.server === 'connected' ? 'Connected' : props.server === 'connecting' ? 'Connecting' : 'Unreachable';
  return (
    <div className="shell">
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
          <div className="row"><span>Build</span><span className="mono">v0.1.0 · SIM</span></div>
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
            <span className="user"><span className="avatar">OP</span>operator01</span>
          </div>
        </div>

        <div className="phead">
          <div>
            <h1>{props.title}</h1>
            <div className="sub">{props.subtitle}</div>
          </div>
          <div className="simbadge">시뮬레이션 화면 예시</div>
        </div>

        <div className="body">{props.children}</div>

        <div className="appfoot">
          <span>AMHS Operations · <span className="mono">v0.1.0</span></span>
          <span className="r"><span>관제시스템</span><span className="mono">가상 데이터 · 시뮬레이션 모드</span></span>
        </div>
      </div>
    </div>
  );
}
