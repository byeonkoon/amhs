import React from 'react';
import { get, overviewApi, type MenuTarget, type Overview } from '../../api';
import { Chip, Empty } from '../../shell/States';
import { aborted } from '../equipment/EquipmentScreen';

/**
 * The site in one answer, for somebody who has just walked up to the screen.
 *
 * <p>Each number says what it counted. A figure on an overview is read as a fact about the site and
 * almost never looked up afterwards, so one that is nearly right is worse here than anywhere else.
 * Where there is no answer the tile says so rather than showing a zero: no job has finished yet and
 * every job finished instantly are different things, and they must not look the same.
 */
export function OverviewScreen({ onGo }: { onGo: (screen: MenuTarget) => void }) {
  const [data, setData] = React.useState<Overview | null>(null);
  const [failed, setFailed] = React.useState(false);

  React.useEffect(() => {
    let live = true;
    const load = () => {
      const c = new AbortController();
      get<Overview>(overviewApi.overview(60), c.signal)
        .then(v => { if (live) { setData(v); setFailed(false); } })
        .catch(cause => { if (!aborted(cause) && live) setFailed(true); });
      return c;
    };
    let controller = load();
    const timer = window.setInterval(() => { controller.abort(); controller = load(); }, 5000);
    return () => { live = false; window.clearInterval(timer); controller.abort(); };
  }, []);

  if (!data) {
    return <section className="card"><div className="cb">
      <p className="hint" style={{ margin: 0 }}>{failed ? '개요를 불러오지 못했습니다.' : '불러오는 중…'}</p>
    </div></section>;
  }

  const { operation, fleet, work, throughput, alarms, stored } = data;
  const running = operation.mode === 'RUNNING';

  return (
    <>
      <div className="statbar">
        <span className="s"><span className="k">사이트</span>
          <span className="v" style={running ? undefined : { color: 'var(--warn)' }}>{MODE[operation.mode] ?? operation.mode}</span>
          <span className="d">{running ? '' : '설정 변경이 가능한 상태입니다'}</span></span>
        <span className="s"><span className="k">운전</span>
          <span className="v">{operation.runMode === 'SIMULATION' ? '시뮬레이션' : '실제 장비'}</span></span>
        <span className="s"><span className="k">자동 배차</span>
          <span className="v" style={operation.autoDispatch ? undefined : { color: 'var(--warn)' }}>
            {operation.autoDispatch ? '켜짐' : '꺼짐'}</span>
          <span className="d">{operation.autoDispatch ? '' : '대기 작업이 저절로 나가지 않습니다'}</span></span>
        <span className="s"><span className="k">갱신</span><span className="v">5초</span>
          <span className="d">{failed ? '마지막으로 읽은 값입니다' : ''}</span></span>
      </div>

      <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(248px, 1fr))', gap: 9 }}>
        <Tile title="알람" onGo={() => onGo('fleet')} go="운전 현황"
          hint="지금 성립하는 조건입니다. 지나간 일이 아니라 지금 사실인 것만 셉니다.">
          <Big value={alarms.critical} tone={alarms.critical ? 'crit' : undefined} label="심각" />
          <Big value={alarms.warning} tone={alarms.warning ? 'warn' : undefined} label="주의" />
        </Tile>

        <Tile title="차량" onGo={() => onGo('fleet')} go="운전 현황"
          hint="‘보고 없음’은 한 번도 위치를 말한 적 없는 차량이고, ‘보고 끊김’은 말하다 멈춘 차량입니다. 둘 중 뒤가 고장입니다.">
          <Big value={fleet.usable} label="작업 가능" of={fleet.configured} />
          <Big value={fleet.working} label="작업 중" />
          <Small value={fleet.outOfService} label="서비스 제외" tone={fleet.outOfService ? 'warn' : undefined} />
          <Small value={fleet.notReporting} label="보고 없음" />
          <Small value={fleet.stale} label="보고 끊김" tone={fleet.stale ? 'warn' : undefined} />
        </Tile>

        <Tile title="작업" onGo={() => onGo('transport')} go="반송 작업"
          hint="‘사람 필요’는 차량이 아니라 사람을 기다리는 작업입니다. 혼자서는 움직이지 않습니다.">
          <Big value={work.running} label="진행 중" />
          <Big value={work.queued} label="대기" tone={work.queued > 0 && !operation.autoDispatch ? 'warn' : undefined} />
          <Small value={work.needingAPerson} label="사람 필요" tone={work.needingAPerson ? 'crit' : undefined} />
          <Small text={work.oldestQueuedSeconds == null ? '없음' : forHowLong(work.oldestQueuedSeconds)}
            label="가장 오래 기다린 대기" />
        </Tile>

        <Tile title={`최근 ${throughput.windowMinutes}분`} onGo={() => onGo('transport')} go="반송 작업"
          hint="소요 시간은 평균이 아니라 중앙값입니다. 크게 어긋난 한 건이 나머지를 대표하지 않게 하기 위해서입니다.">
          <Big value={throughput.completed} label="완료" />
          <Small value={throughput.cancelled} label="취소" />
          <Small value={throughput.failed} label="실패" tone={throughput.failed ? 'crit' : undefined} />
          {/* No answer is said as no answer. A dash is not zero. */}
          <Small text={throughput.medianSeconds == null ? '—' : forHowLong(throughput.medianSeconds)}
            label={throughput.measured === 0 ? '소요 시간 · 완료 없음' : `소요 시간 중앙값 · ${throughput.measured}건 기준`} />
        </Tile>

        <Tile title="보관" onGo={() => onGo('inventory')} go="재고"
          hint="‘셀 미지정’은 포트에서 받아 간 것까지만 확인된 상태입니다. 위치를 모르는 것과는 다릅니다.">
          <Big value={stored.inCells} label="셀에 기록" />
          <Big value={stored.atPortOnly} label="셀 미지정" tone={stored.atPortOnly ? 'warn' : undefined} />
          <Small value={stored.inTransport} label="반송 중" />
          <Small text={`${stored.cells - stored.blockedCells} / ${stored.cells}`} label="쓸 수 있는 셀" />
        </Tile>
      </div>

      {alarms.total === 0 && work.needingAPerson === 0 && fleet.stale === 0 && (
        <section className="card">
          <Empty title="손이 필요한 일이 없습니다"
            hint="알람이 없고, 사람을 기다리는 작업도 없고, 보고가 끊긴 차량도 없습니다." />
        </section>
      )}
    </>
  );
}

function Tile({ title, hint, go, onGo, children }:
  { title: string; hint: string; go: string; onGo: () => void; children: React.ReactNode }) {
  return (
    <section className="card" style={{ display: 'flex', flexDirection: 'column' }}>
      <div className="ch">
        <h2>{title}</h2>
        <button type="button" className="aux" onClick={onGo}
          style={{ border: 0, background: 'none', cursor: 'pointer', color: 'var(--acc)', font: 'inherit' }}>
          {go} ›
        </button>
      </div>
      <div className="cb" style={{ display: 'flex', gap: 14, flexWrap: 'wrap', alignItems: 'flex-end', flex: 1 }}>
        {children}
      </div>
      <p className="hint" style={{ padding: '0 15px 13px', margin: 0 }}>{hint}</p>
    </section>
  );
}

function Big({ value, label, of, tone }: { value: number; label: string; of?: number; tone?: 'crit' | 'warn' }) {
  return (
    <span style={{ display: 'grid', gap: 1 }}>
      <span className="mono" style={{ fontSize: 25, lineHeight: 1.05, color: toneColor(tone) }}>
        {value}{of !== undefined && <span style={{ fontSize: 13, color: 'var(--mut)' }}> / {of}</span>}
      </span>
      <span style={{ fontSize: 11, color: 'var(--mut)' }}>{label}</span>
    </span>
  );
}
function Small({ value, text, label, tone }:
  { value?: number; text?: string; label: string; tone?: 'crit' | 'warn' }) {
  return (
    <span style={{ display: 'grid', gap: 1 }}>
      <span className="mono" style={{ fontSize: 14, color: toneColor(tone) }}>{text ?? value}</span>
      <span style={{ fontSize: 10.5, color: 'var(--mut)' }}>{label}</span>
    </span>
  );
}
function toneColor(tone?: 'crit' | 'warn') {
  return tone === 'crit' ? 'var(--crit)' : tone === 'warn' ? 'var(--warn)' : undefined;
}

/** Durations as somebody would say them, so nobody divides seconds in their head. */
function forHowLong(seconds: number): string {
  if (seconds < 60) return `${Math.round(seconds)}초`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}분 ${Math.round(seconds % 60)}초`;
  const hours = Math.floor(seconds / 3600);
  return `${hours}시간 ${Math.floor((seconds % 3600) / 60)}분`;
}

const MODE: Record<string, string> = {
  RUNNING: '가동 중', MAINTENANCE: '유지보수', RECOVERING: '복구 중',
};
