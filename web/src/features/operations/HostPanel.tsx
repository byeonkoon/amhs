import React from 'react';
import { SITE_ID, get } from '../../api';
import { Chip } from '../../shell/States';

type Known = 'KNOWN' | 'STALE' | 'UNKNOWN' | 'UNSUPPORTED';
type Metric = { name: string; value: number | null; unit: string | null; state: Known; source: string; measuredAt: string; note: string | null };
type Nic = { name: string; displayName: string; state: Known; up: boolean | null; loopback: boolean; virtual: boolean; mtu: number | null;
  addresses: string[]; speed: Metric; errors: Metric };
type Reach = { name: string; address: string; state: Known; millis: number | null; measuredAt: string | null; note: string | null };
type Link = { vehicleId: number; code: string; state: Known; online: boolean; source: string; lastReport: string | null;
  ageSeconds: number | null; unsettledCommands: number };
type Report = { hostId: string; at: string; host: Metric[]; nics: Nic[]; accessPoints: Reach[]; broker: Metric; vehicles: Link[] };

const STATE: Record<Known, { tone: string; label: string }> = {
  KNOWN: { tone: 'ok', label: '측정' }, STALE: { tone: 'wr', label: '오래됨' },
  UNKNOWN: { tone: 'er', label: '알 수 없음' }, UNSUPPORTED: { tone: 'id', label: '수집 불가' },
};
const NAME: Record<string, string> = {
  'cpu.system': 'CPU(시스템)', 'cpu.process': 'CPU(서버 프로세스)', 'memory.total': '메모리 전체', 'memory.free': '메모리 여유',
  'jvm.heap.used': 'JVM 힙 사용', 'jvm.heap.max': 'JVM 힙 상한', 'jvm.threads': 'JVM 스레드', 'jvm.uptime': '서버 가동 시간',
  'disk.total': '디스크 전체', 'disk.usable': '디스크 여유',
};

/** A value as read, or why there is none: never a zero standing in for "not known". */
function show(m: Metric): string {
  if (m.state !== 'KNOWN' || m.value == null) return '—';
  // A small measured value must not round to a zero that reads as "nothing".
  if (m.unit === 'bytes') return m.value >= 1024 ** 3 ? `${(m.value / 1024 ** 3).toFixed(1)} GB` : `${Math.round(m.value / 1024 ** 2)} MB`;
  if (m.unit === '%') return `${m.value.toFixed(1)}%`;
  if (m.unit === 's') return `${Math.floor(m.value / 3600)}시간 ${Math.floor((m.value % 3600) / 60)}분`;
  return `${m.value}${m.unit && m.unit !== 'count' ? ` ${m.unit}` : ''}`;
}

/**
 * The server's own host, its network, any access point somebody asked to be checked, and each
 * vehicle's link -- kept apart, each with how well it is known. The browser's machine is not here:
 * nothing on the server can see it.
 */
export function HostPanel() {
  const [report, setReport] = React.useState<Report | null>(null);
  const [failed, setFailed] = React.useState(false);
  React.useEffect(() => {
    let live = true;
    const load = () => {
      const c = new AbortController();
      get<Report>(`/api/v1/sites/${SITE_ID}/diagnostics/host`, c.signal)
        .then(r => { if (live) { setReport(r); setFailed(false); } })
        .catch(e => { if ((e as Error).name !== 'AbortError' && live) setFailed(true); });
      return c;
    };
    let controller = load();
    const timer = window.setInterval(() => { controller.abort(); controller = load(); }, 5000);
    return () => { live = false; window.clearInterval(timer); controller.abort(); };
  }, []);
  const at = (t: string | null) => t ? new Date(t).toLocaleTimeString('ko-KR', { hour12: false }) : '—';
  return (
    <section className="card">
      <div className="ch">
        <h2>서버 호스트·네트워크·차량 연결</h2>
        <span className="aux">{report ? `수집 호스트 ${report.hostId} · ${at(report.at)}` : ''}
          {failed ? ' · 최신 값을 읽지 못했습니다 — 아래는 마지막으로 읽은 값입니다' : ''}</span>
      </div>
      {!report ? <div className="cb"><p className="hint" style={{ margin: 0 }}>{failed ? '진단 값을 읽지 못했습니다.' : '불러오는 중…'}</p></div> : (
        <div className="cb" style={{ display: 'grid', gap: 12 }}>
          <div className="tbl-scroll">
            <table className="t">
              <thead><tr><th>항목</th><th>값</th><th>상태</th><th>수집원</th><th>비고</th></tr></thead>
              <tbody>
                {[...report.host, report.broker].map(m => (
                  <tr key={m.name}>
                    <td>{m.name === 'broker' ? 'MQTT broker 연결' : NAME[m.name] ?? m.name}</td>
                    <td className="mono">{show(m)}</td>
                    <td><Chip tone={STATE[m.state].tone}>{STATE[m.state].label}</Chip></td>
                    <td className="mono">{m.source}</td>
                    <td className="hint">{m.note ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="tbl-scroll">
            <table className="t">
              <thead><tr><th>네트워크 인터페이스</th><th>링크</th><th>주소</th><th>MTU</th><th>속도</th><th>오류 수</th></tr></thead>
              <tbody>
                {report.nics.map(n => (
                  <tr key={n.name}>
                    <td title={n.name}>{n.displayName}{n.loopback ? ' (loopback)' : n.virtual ? ' (가상)' : ''}</td>
                    <td>{n.up == null ? <Chip tone="er">알 수 없음</Chip> : <Chip tone={n.up ? 'ok' : 'wr'}>{n.up ? '연결' : '끊김'}</Chip>}</td>
                    <td className="mono">{n.addresses.join(', ') || '—'}</td>
                    <td className="mono">{n.mtu ?? '—'}</td>
                    <td title={n.speed.note ?? ''}><Chip tone="id">{STATE[n.speed.state].label}</Chip></td>
                    <td title={n.errors.note ?? ''}><Chip tone="id">{STATE[n.errors.state].label}</Chip></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="tbl-scroll">
            <table className="t">
              <thead><tr><th>AP·장비 도달성</th><th>주소</th><th>상태</th><th>응답</th><th>확인 시각</th></tr></thead>
              <tbody>
                {report.accessPoints.length === 0
                  ? <tr><td colSpan={5} className="hint">확인할 AP가 지정되지 않았습니다(amhs.diagnostics.reach). 신호 세기·접속 대수는 AP 장비 조회가 필요해 수집하지 않습니다.</td></tr>
                  : report.accessPoints.map(r => (
                    <tr key={r.name}>
                      <td>{r.name}</td><td className="mono">{r.address}</td>
                      <td><Chip tone={STATE[r.state].tone}>{STATE[r.state].label}</Chip></td>
                      <td className="mono">{r.state === 'KNOWN' ? (r.millis != null ? `${r.millis} ms` : (r.note ?? '응답 없음')) : (r.note ?? '—')}</td>
                      <td className="mono">{at(r.measuredAt)}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
          <div className="tbl-scroll">
            <table className="t">
              <thead><tr><th>차량</th><th>수신</th><th>온라인</th><th>마지막 수신</th><th>경과</th><th>수신원</th><th>미정리 명령</th></tr></thead>
              <tbody>
                {report.vehicles.map(v => (
                  <tr key={v.vehicleId}>
                    <td className="mono">{v.code}</td>
                    <td><Chip tone={STATE[v.state].tone}>{v.state === 'UNKNOWN' ? '보고 없음' : STATE[v.state].label}</Chip></td>
                    <td>{v.state === 'UNKNOWN' ? '—' : v.online ? '예' : '아니오'}</td>
                    <td className="mono">{at(v.lastReport)}</td>
                    <td className="mono">{v.ageSeconds == null ? '—' : `${v.ageSeconds}초`}</td>
                    <td className="mono">{v.source}</td>
                    <td className="mono">{v.unsettledCommands}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </section>
  );
}
