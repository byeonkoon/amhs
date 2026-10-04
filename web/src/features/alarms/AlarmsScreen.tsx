import React from 'react';
import { ApiError, alarmApi, get, hasWriteToken, send, type Alarm, type MenuTarget } from '../../api';
import { Chip, Empty } from '../../shell/States';
import { Grid, Row } from '../../shell/Panel';
import { aborted } from '../equipment/EquipmentScreen';

/**
 * What is wrong now.
 *
 * <p>Every line is a condition the server worked out from the site this moment, not an event it
 * remembered. So the list cannot say something is wrong after it has stopped being wrong, and
 * nothing is missed for having happened while nobody was watching. Clearing happens by the thing
 * being fixed; there is nothing here to dismiss, because dismissing a condition that is still true
 * is how a real fault leaves a screen.
 *
 * <p>Each line says what the condition is already causing, not only what it is. That is the part a
 * person decides on, and a screen that leaves it out gets scrolled past.
 */
export function AlarmsScreen({ onGo }: { onGo: (screen: MenuTarget) => void }) {
  const [alarms, setAlarms] = React.useState<Alarm[] | null>(null);
  const [failed, setFailed] = React.useState(false);
  const [notice, setNotice] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);

  /**
   * Records that somebody looked. It does not clear the line, and the button never implies it
   * will: a control that removed a true condition would be a control for hiding faults.
   */
  async function markSeen(alarm: Alarm) {
    const reason = window.prompt(
      `${alarm.rule} · ${alarm.subject}\n\n무엇을 확인했는지, 또는 무엇을 하고 있는지 적어 주세요.`
      + `\n조건은 그대로 남습니다 — 고쳐야 사라집니다.`);
    if (reason === null || !reason.trim()) return;
    setBusy(true); setNotice(null);
    try {
      const updated = await send<Alarm>(alarmApi.seen(alarm.key), 'PUT', { reason }, '알람 확인');
      setAlarms(list => list?.map(a => a.key === updated.key ? updated : a) ?? list);
    } catch (cause) {
      setNotice(cause instanceof ApiError ? explainAck(cause.message) : '기록하지 못했습니다.');
    } finally { setBusy(false); }
  }

  React.useEffect(() => {
    let live = true;
    const load = () => {
      const c = new AbortController();
      get<Alarm[]>(alarmApi.alarms(), c.signal)
        .then(list => { if (live) { setAlarms(list); setFailed(false); } })
        .catch(cause => { if (!aborted(cause) && live) setFailed(true); });
      return c;
    };
    let controller = load();
    const timer = window.setInterval(() => { controller.abort(); controller = load(); }, 3000);
    return () => { live = false; window.clearInterval(timer); controller.abort(); };
  }, []);

  if (alarms === null) {
    return (
      <section className="card"><div className="cb">
        <p className="hint" style={{ margin: 0 }}>{failed ? '알람을 불러오지 못했습니다.' : '불러오는 중…'}</p>
      </div></section>
    );
  }

  const critical = alarms.filter(a => a.severity === 'CRITICAL');
  const warning = alarms.filter(a => a.severity === 'WARNING');
  const unseen = alarms.filter(a => !a.seen);

  return (
    <>
      <div className="statbar">
        <span className="s"><span className="k">심각</span>
          <span className="v" style={critical.length ? { color: 'var(--crit)' } : undefined}>{critical.length}</span>
          <span className="d">{critical.length ? '지금 손이 필요합니다' : ''}</span></span>
        <span className="s"><span className="k">주의</span>
          <span className="v" style={warning.length ? { color: 'var(--warn)' } : undefined}>{warning.length}</span></span>
        <span className="s"><span className="k">미확인</span>
          <span className="v" style={unseen.length ? { color: 'var(--warn)' } : undefined}>{unseen.length}</span>
          <span className="d">{unseen.length ? '아무도 봤다고 하지 않았습니다' : ''}</span></span>
        <span className="s"><span className="k">갱신</span><span className="v">3초</span>
          <span className="d">{failed ? '서버를 읽지 못했습니다 · 아래는 마지막으로 읽은 내용입니다' : '지금 상태에서 매번 다시 판정합니다'}</span></span>
      </div>

      {notice && <div className="card"><div className="cb" style={{ padding: '10px 14px' }}>
        <p style={{ margin: 0, fontSize: 12 }}><Chip tone="er">거부</Chip> {notice}</p>
      </div></div>}

      {alarms.length === 0
        ? <section className="card">
            <Empty title="알람이 없습니다"
              hint="이 목록은 기억된 사건이 아니라 지금 사실인 조건입니다. 비어 있다는 것은 조건이 하나도 성립하지 않는다는 뜻입니다." />
          </section>
        : (
          <section className="card">
            <div className="ch">
              <h2>지금 성립하는 조건</h2>
              <span className="aux">해제할 항목이 없습니다 — 조건이 사라지면 줄도 사라집니다</span>
            </div>
            <Grid cols="54px 104px 150px 1fr 128px 132px"
              head={['등급', '규칙', '대상', '내용과 그 영향', '시각', '확인']}>
              {alarms.map(a => (
                <Row key={a.key}>
                  <span>
                    <Chip tone={a.severity === 'CRITICAL' ? 'er' : 'wr'}>
                      {a.severity === 'CRITICAL' ? '심각' : '주의'}
                    </Chip>
                  </span>
                  <span style={{ fontSize: 11.5 }}>{a.rule}</span>
                  <span className="mono" style={{ fontSize: 11, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                    title={a.subject}>{a.subject}</span>
                  <span style={{ minWidth: 0 }}>
                    <div style={{ fontSize: 12 }}>{a.summary}</div>
                    {/* The consequence, not a repeat of the condition: this is what is decided on. */}
                    <div className="hint" style={{ margin: 0 }}>{a.effect}</div>
                  </span>
                  <span style={{ fontSize: 10.5, color: 'var(--mut)' }}>
                    {a.since ? <><div className="mono">{when(a.since)}</div><div>{a.sinceMeans}</div></>
                      : <span title="이 조건이 언제부터였는지는 어디에도 기록되어 있지 않습니다.">{a.sinceMeans}</span>}
                  </span>
                  <span style={{ display: 'grid', gap: 3, minWidth: 0 }}>
                    {a.seen
                      // Said, not shown as cleared: the line stays because the condition does.
                      ? <span title={`${a.seen.reason}${a.seen.actorCode ? ` · ${a.seen.actorCode}` : ''} · ${when(a.seen.at)}`}
                          style={{ fontSize: 10.5, color: 'var(--mut)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          <Chip tone="id">확인됨</Chip> {a.seen.actorCode ?? ''}
                        </span>
                      : <button type="button" style={ghost} disabled={busy || !hasWriteToken()}
                          title={hasWriteToken()
                            ? '봤다는 것만 남깁니다. 조건은 그대로이며 고쳐야 사라집니다.'
                            : 'Lv2 운전 이상으로 로그인해야 기록할 수 있습니다.'}
                          onClick={() => markSeen(a)}>확인함</button>}
                    <button type="button" style={ghost} onClick={() => onGo(a.screen)}>
                      {SCREEN_LABEL[a.screen]} ›
                    </button>
                  </span>
                </Row>
              ))}
            </Grid>
            <p className="hint" style={{ padding: '9px 15px 14px', margin: 0 }}>
              이 목록은 저장된 사건 기록이 아닙니다. 매번 사이트의 현재 상태에서 다시 판정하므로, 이미 끝난
              일이 남아 있거나 아무도 보고 있지 않은 동안 생긴 일이 빠지는 일이 없습니다. 지나간 일은 이력
              화면에서 봅니다.
              <br />
              <b>‘확인함’은 봤다는 기록일 뿐 해제가 아닙니다.</b> 조건이 사라져야 줄도 사라집니다. 조건이
              끝나면 그 확인 기록도 함께 폐기되므로, 같은 고장이 다시 나면 다시 미확인으로 올라옵니다 —
              몇 주 전에 다른 건을 본 사람의 확인이 오늘의 고장을 조용히 덮지 않습니다.
            </p>
          </section>
        )}
    </>
  );
}

function explainAck(code: string): string {
  if (code === 'ALARM_NOT_LIVE') return '그 조건은 이미 사라졌습니다. 없는 것을 확인해 둘 수는 없습니다 — 목록을 다시 읽어 주세요.';
  if (code === 'ACKNOWLEDGE_REASON_REQUIRED') return '무엇을 확인했는지 적어야 합니다. 눌렀다는 사실만으로는 다음 사람에게 알려줄 것이 없습니다.';
  if (code === 'ROLE_OPERATOR_REQUIRED') return 'Lv2 운전 이상 권한이 필요합니다.';
  return code;
}

const SCREEN_LABEL: Record<MenuTarget, string> = {
  fleet: '운전 현황', transport: '반송 작업', traffic: '구간 점유', diagnostics: '진단', inventory: '재고',
};

/** Clock time is what an operator compares against the wall; the date only when it is not today. */
function when(value: string): string {
  const at = new Date(value);
  const p = (v: number) => String(v).padStart(2, '0');
  const clock = `${p(at.getHours())}:${p(at.getMinutes())}:${p(at.getSeconds())}`;
  const today = new Date();
  const sameDay = at.getFullYear() === today.getFullYear() && at.getMonth() === today.getMonth()
    && at.getDate() === today.getDate();
  return sameDay ? clock : `${p(at.getMonth() + 1)}-${p(at.getDate())} ${clock}`;
}

const ghost: React.CSSProperties = {
  border: '1px solid var(--ln2)', background: 'var(--srf)', color: 'var(--ink2)',
  borderRadius: 4, padding: '2px 8px', fontSize: 10.5, cursor: 'pointer', whiteSpace: 'nowrap',
};
