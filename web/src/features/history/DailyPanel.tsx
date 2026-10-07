import React from 'react';
import { ApiError, SITE_ID, get } from '../../api';
import { Chip, Empty } from '../../shell/States';

type Day = { date: string; fromUtc: string; toUtc: string; completed: number; cancelledAfterStart: number; failed: number;
  cancelledBeforeStart: number; normalRatePct: number | null };
type Reason = { reason: string; afterStart: number; beforeStart: number };
type Abnormal = { orderId: number; status: string; started: boolean; cancelReason: string | null; endedAt: string };
type Report = { zone: string; policy: string; days: Day[]; cancelReasons: Reason[]; abnormal: Abnormal[]; abnormalTotal: number;
  open: { underWay: number; waitingForPerson: number } };

const ymd = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

/**
 * How the work of each site day ended, as the order ledger records it. A day with nothing to divide
 * by shows no rate (N/A), never 0% or 100%. This counts how work ended; it is not a carrier inventory.
 */
export function DailyPanel() {
  const today = new Date();
  const [from, setFrom] = React.useState(ymd(new Date(today.getTime() - 6 * 86400000)));
  const [to, setTo] = React.useState(ymd(today));
  const [report, setReport] = React.useState<Report | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [reload, setReload] = React.useState(0);

  React.useEffect(() => {
    // The server refuses these too (422); saying why here is clearer than a generic refusal.
    if (from > to) { setError('시작일이 종료일보다 늦습니다.'); return; }
    if ((Date.parse(to) - Date.parse(from)) / 86400000 > 366) { setError('한 번에 366일까지 조회할 수 있습니다.'); return; }
    const c = new AbortController();
    setError(null);
    get<Report>(`/api/v1/sites/${SITE_ID}/statistics/daily?from=${from}&to=${to}`, c.signal).then(setReport)
      .catch(cause => {
        if ((cause as Error).name === 'AbortError') return;
        setError(cause instanceof ApiError ? cause.message : '서버에 연결하지 못했습니다.');
      });
    return () => c.abort();
  }, [from, to, reload]);

  const time = (t: string) => new Date(t).toLocaleString('ko-KR', { hour12: false });
  const total = report?.days.reduce((s, d) => ({
    completed: s.completed + d.completed, after: s.after + d.cancelledAfterStart, failed: s.failed + d.failed,
    before: s.before + d.cancelledBeforeStart }), { completed: 0, after: 0, failed: 0, before: 0 });
  const totalRate = total && total.completed + total.after + total.failed > 0
    ? Math.round(1000 * total.completed / (total.completed + total.after + total.failed)) / 10 : null;
  const rate = (r: number | null) => r == null ? <span className="hint">N/A</span> : `${r.toFixed(1)}%`;

  return (
    <section className="card">
      <div className="ch">
        <h2>날짜별 처리 결과</h2>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 6, alignItems: 'center', fontSize: 12 }}>
          <input type="date" value={from} onChange={e => e.target.value && setFrom(e.target.value)} aria-label="시작일" />
          <span>~</span>
          <input type="date" value={to} onChange={e => e.target.value && setTo(e.target.value)} aria-label="종료일" />
        </div>
      </div>
      <div className="cb" style={{ paddingBottom: 0 }}>
        <p className="hint" style={{ margin: 0 }}>
          하루는 사이트 시간대{report ? `(${report.zone})` : ''}의 00:00부터 다음 00:00 전까지이며, 작업이 끝난 시각으로 집계합니다.
          정상 완료율 = 완료 ÷ (완료 + 시작 후 취소 + 실패). 시작 전 취소는 비율에서 빼고 따로 셉니다.
          {report ? ` 기준 ${report.policy}` : ''}
        </p>
      </div>
      {error && (
        <div className="cb" style={{ paddingBottom: 0 }}>
          <p style={{ margin: 0, fontSize: 12 }}><Chip tone="er">조회 실패</Chip> {error}
            {report !== null && ' 아래는 마지막으로 읽은 값입니다.'}{' '}
            <button type="button" onClick={() => setReload(v => v + 1)} style={retryStyle}>다시 읽기</button></p>
        </div>
      )}
      {report === null ? (error ? null : <div className="cb"><p className="hint" style={{ margin: 0 }}>불러오는 중…</p></div>) : (
        <div className="cb" style={{ display: 'grid', gap: 12 }}>
          <div className="tbl-scroll">
            <table className="t">
              <thead><tr><th>날짜</th><th>완료</th><th>시작 후 취소</th><th>실패</th><th>정상 완료율</th><th>시작 전 취소</th></tr></thead>
              <tbody>
                {report.days.map(d => (
                  <tr key={d.date}>
                    <td className="mono">{d.date}</td>
                    <td className="mono">{d.completed}</td>
                    <td className="mono">{d.cancelledAfterStart}</td>
                    <td className="mono">{d.failed}</td>
                    <td className="mono">{rate(d.normalRatePct)}</td>
                    <td className="mono">{d.cancelledBeforeStart}</td>
                  </tr>
                ))}
                {total && (
                  <tr style={{ fontWeight: 600 }}>
                    <td>합계</td><td className="mono">{total.completed}</td><td className="mono">{total.after}</td>
                    <td className="mono">{total.failed}</td><td className="mono">{rate(totalRate)}</td><td className="mono">{total.before}</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <p className="hint" style={{ margin: 0 }}>
            이 기간에 접수돼 아직 끝나지 않은 작업: 진행 중 {report.open.underWay}건, 사람 확인 대기 {report.open.waitingForPerson}건 (비율에 넣지 않음)
          </p>
          <div className="tbl-scroll">
            <table className="t">
              <thead><tr><th>취소 사유</th><th>시작 후</th><th>시작 전</th></tr></thead>
              <tbody>
                {report.cancelReasons.length === 0
                  ? <tr><td colSpan={3} className="hint">이 기간에 취소된 작업이 없습니다.</td></tr>
                  : report.cancelReasons.map(r => (
                    <tr key={r.reason}><td>{r.reason}</td><td className="mono">{r.afterStart}</td><td className="mono">{r.beforeStart}</td></tr>
                  ))}
              </tbody>
            </table>
          </div>
          {report.abnormalTotal === 0 ? <Empty title="이 기간에 실패나 시작 후 취소된 작업이 없습니다" /> : (
            <div className="tbl-scroll">
              <table className="t">
                <thead><tr><th>번호</th><th>결과</th><th>사유</th><th>종료</th></tr></thead>
                <tbody>
                  {report.abnormal.map(a => (
                    <tr key={a.orderId}>
                      <td className="mono">{a.orderId}</td>
                      <td><Chip tone={a.status === 'FAILED' ? 'er' : 'wr'}>{a.status === 'FAILED' ? '실패' : '시작 후 취소'}</Chip></td>
                      <td>{a.cancelReason ?? '—'}</td>
                      <td className="mono">{time(a.endedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {report.abnormalTotal > report.abnormal.length &&
                <p className="hint" style={{ margin: '6px 0 0' }}>전체 {report.abnormalTotal}건 중 최근 {report.abnormal.length}건만 표시합니다.</p>}
            </div>
          )}
        </div>
      )}
    </section>
  );
}

const retryStyle: React.CSSProperties = {
  border: '1px solid var(--ln2)', background: 'var(--srf)', color: 'var(--ink2)', borderRadius: 4,
  padding: '1px 8px', fontSize: 11, cursor: 'pointer',
};
