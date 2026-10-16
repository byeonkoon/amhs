import React from 'react';
import {
  ApiError, equipmentApi, get, opsApi, send, simulationEquipmentApi,
  type EquipmentState, type EquipmentStatement, type SimulatedEquipmentState,
  type Cfg, type EquipmentSettings, type RackSettings, type Session, type SlotView,
} from '../../api';
import { Chip, Empty } from '../../shell/States';
import { Grid, Panel, Row } from '../../shell/Panel';

/**
 * Equipment the controller serves, with its racks and cells.
 *
 * <p>The cells come from the rack's size rather than being entered, so the two cannot disagree.
 * Marking a cell unusable is a decision someone records about a real place; it is deliberately not
 * occupancy and not a reservation, and this screen never presents it as either.
 */
export function EquipmentScreen({ session, simulated }: { session: Session; simulated: boolean }) {
  const [units, setUnits] = React.useState<Cfg<EquipmentSettings>[] | null>(null);
  const [unitId, setUnitId] = React.useState<number | null>(null);
  const [racks, setRacks] = React.useState<Cfg<RackSettings>[]>([]);
  const [rackId, setRackId] = React.useState<number | null>(null);
  const [slots, setSlots] = React.useState<SlotView[]>([]);
  const [notice, setNotice] = React.useState<{ tone: string; text: string } | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [reload, setReload] = React.useState(0);

  const mayEdit = session.allows.includes('equipment');

  React.useEffect(() => {
    const c = new AbortController();
    get<Cfg<EquipmentSettings>[]>(opsApi.equipment(), c.signal)
      .then(list => { setUnits(list); setUnitId(id => id ?? list[0]?.id ?? null); })
      // An aborted request is this effect being replaced, not an empty answer: writing [] here
      // would let a cancelled first load overwrite the one that succeeded.
      .catch(cause => { if (!aborted(cause)) setUnits([]); });
    return () => c.abort();
  }, [reload]);

  React.useEffect(() => {
    if (unitId === null) { setRacks([]); setRackId(null); return; }
    const c = new AbortController();
    get<Cfg<RackSettings>[]>(opsApi.racks(unitId), c.signal)
      .then(list => { setRacks(list); setRackId(id => list.some(r => r.id === id) ? id : list[0]?.id ?? null); })
      .catch(cause => { if (!aborted(cause)) setRacks([]); });
    return () => c.abort();
  }, [unitId, reload]);

  React.useEffect(() => {
    if (unitId === null || rackId === null) { setSlots([]); return; }
    const c = new AbortController();
    get<SlotView[]>(opsApi.slots(unitId, rackId), c.signal)
      .then(setSlots).catch(cause => { if (!aborted(cause)) setSlots([]); });
    return () => c.abort();
  }, [unitId, rackId, reload]);

  async function toggle(slot: SlotView) {
    if (unitId === null || rackId === null) return;
    const reason = slot.usable ? window.prompt('사용 금지 사유를 적어 주세요. 왜 비워 두는지가 기록으로 남습니다.') : null;
    if (slot.usable && (reason === null || !reason.trim())) return;
    setBusy(true); setNotice(null);
    try {
      await send<SlotView>(`${opsApi.slots(unitId, rackId)}/${slot.id}`, 'PUT',
        { usable: !slot.usable, unusableReason: reason }, '셀 사용 여부 변경');
      setReload(v => v + 1);
    } catch (cause) {
      setNotice({ tone: 'er', text: cause instanceof ApiError ? explain(cause.message) : '변경하지 못했습니다.' });
    } finally { setBusy(false); }
  }

  const unit = units?.find(u => u.id === unitId) ?? null;
  const rack = racks.find(r => r.id === rackId) ?? null;
  const blocked = slots.filter(s => !s.usable).length;

  if (units === null) return <section className="card"><div className="cb"><p className="hint" style={{ margin: 0 }}>불러오는 중…</p></div></section>;

  return (
    <>
      <div className="statbar">
        <span className="s"><span className="k">설비</span><span className="v">{units.length}</span></span>
        <span className="s"><span className="k">랙</span><span className="v">{racks.length}</span></span>
        <span className="s"><span className="k">셀</span><span className="v">{slots.length}</span>
          <span className="d">{rack ? `${rack.settings.levels}단 × ${rack.settings.columns}열` : ''}</span></span>
        <span className="s"><span className="k">사용 금지</span>
          <span className="v" style={blocked ? { color: 'var(--warn)' } : undefined}>{blocked}</span></span>
        <span className="s"><span className="k">변경</span><span className="v">{mayEdit ? '가능' : '불가'}</span>
          <span className="d">{mayEdit ? '' : '설비 설정 권한이 필요합니다'}</span></span>
      </div>

      {notice && <div className="card"><div className="cb" style={{ padding: '10px 14px' }}>
        <p style={{ margin: 0, fontSize: 12 }}><Chip tone={notice.tone}>거부</Chip> {notice.text}</p>
      </div></div>}

      <EquipmentLinkState simulated={simulated} />

      {!simulated && <EquipmentStatements />}

      {simulated && <SimulationControls unitId={unitId} mayControl={session.level >= 2} />}

      {units.length === 0
        ? <section className="card"><Empty title="등록된 설비가 없습니다"
            hint="설비는 제어 대상이 아니라 제어기가 마주 보는 상대입니다. 자원 구성의 포트가 그 접점입니다." /></section>
        : (
          <div className="grid g-mon">
            <div style={{ display: 'grid', gap: 9, alignContent: 'start' }}>
              <Panel id="eq-units" title="설비" aux={`${units.length}대`} defaultOpen>
                <Grid cols="76px 1fr 66px 54px" head={['코드', '이름', '종류', '사용']}>
                  {units.map(u => (
                    <Row key={u.id} selected={u.id === unitId} onClick={() => setUnitId(u.id)}>
                      <span className="mono">{u.settings.code}</span>
                      <span>{u.settings.name || '—'}</span>
                      <span><Chip tone={u.settings.kind === 'STOCKER' ? 'ac' : 'id'}>{KIND[u.settings.kind]}</Chip></span>
                      <span><Chip tone={u.settings.enabled ? 'ok' : 'id'}>{u.settings.enabled ? '사용' : '중지'}</Chip></span>
                    </Row>
                  ))}
                </Grid>
              </Panel>

              <Panel id="eq-racks" title="랙" aux={unit?.settings.code ?? '—'} defaultOpen>
                {racks.length === 0
                  ? <Empty title="이 설비에 등록된 랙이 없습니다" />
                  : (
                    <Grid cols="76px 66px 66px 54px" head={['코드', '단', '열', '사용']}>
                      {racks.map(r => (
                        <Row key={r.id} selected={r.id === rackId} onClick={() => setRackId(r.id)}>
                          <span className="mono">{r.settings.code}</span>
                          <span className="mono">{r.settings.levels}</span>
                          <span className="mono">{r.settings.columns}</span>
                          <span><Chip tone={r.settings.enabled ? 'ok' : 'id'}>{r.settings.enabled ? '사용' : '중지'}</Chip></span>
                        </Row>
                      ))}
                    </Grid>
                  )}
              </Panel>
            </div>

            <section className="card">
              <div className="ch">
                <h2>셀</h2>
                <span className="aux">
                  사용 금지는 비워 두기로 한 결정입니다. 점유나 예약, 제품 위치와는 다른 사실입니다
                </span>
              </div>
              <div className="cb">
                {slots.length === 0
                  ? <Empty title="셀이 없습니다" hint="랙을 고르면 그 크기에서 생성된 셀이 표시됩니다." />
                  : <SlotGrid slots={slots} columns={rack?.settings.columns ?? 1}
                      onToggle={mayEdit && !busy ? toggle : undefined} />}
              </div>
            </section>
          </div>
        )}
    </>
  );
}

function SimulationControls({ unitId, mayControl }: { unitId: number | null; mayControl: boolean }) {
  const [states, setStates] = React.useState<SimulatedEquipmentState[]>([]);
  const [error, setError] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [reload, setReload] = React.useState(0);
  React.useEffect(() => {
    const c = new AbortController();
    let active = true;
    const read = () => get<SimulatedEquipmentState[]>(simulationEquipmentApi.list(), c.signal)
      .then(v => { if (active) { setStates(v); setError(''); } })
      .catch(e => { if (active && !aborted(e)) { setStates([]); setError('설비 시뮬레이션 상태를 읽지 못했습니다.'); } });
    void read();
    const timer = window.setInterval(read, 1500);
    return () => { active = false; c.abort(); window.clearInterval(timer); };
  }, [reload]);
  const state = states.find(s => s.unitId === unitId);
  async function change(patch: Partial<Pick<SimulatedEquipmentState, 'enabled' | 'callEnabled' | 'status'>>) {
    if (unitId === null) return;
    setBusy(true); setError('');
    try {
      await send(simulationEquipmentApi.state(unitId), 'PUT', patch, '시뮬레이션 설비 상태 변경');
      setReload(v => v + 1);
    } catch (e) { setError(e instanceof ApiError ? explain(e.message) : '변경하지 못했습니다.'); }
    finally { setBusy(false); }
  }
  const disabled = busy || !mayControl;
  return <section className="card"><div className="ch"><h2>설비 시뮬레이션</h2></div><div className="cb">
    <p className="hint">가상 상태는 직접 변경할 수 있습니다. 시뮬레이션에서는 사용금지·콜금지만 작업을 차단하고 나머지 상태는 제한하지 않습니다. 운전 모드가 OPERATION이면 이 값들은 쓰이지 않고, 설비가 링크로 말한 것이 판단 근거가 됩니다.</p>
    {error && <p role="alert">{error}</p>}
    {!state ? <p>설비를 선택해 주세요.</p> : <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
      <label><input type="checkbox" checked={state.enabled} disabled={disabled}
        onChange={e => void change({ enabled: e.target.checked })} /> 시뮬레이션 사용</label>
      <label><input type="checkbox" checked={state.callEnabled} disabled={disabled}
        onChange={e => void change({ callEnabled: e.target.checked })} /> 콜 허용</label>
      <label>가상 상태 <select value={state.status}
        disabled={disabled || !state.configuredEnabled || !state.enabled || !state.callEnabled}
        onChange={e => void change({ status: e.target.value })}>
        {Object.entries(SIM_STATES).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select></label>
      {!state.configuredEnabled && <span>설비 설정에서 사용금지 상태입니다.</span>}
      {!mayControl && <span>운전 권한이 필요합니다.</span>}
    </div>}
  </div></section>;
}
const SIM_STATES: Record<string, string> = { IDLE: '대기', READY: '준비', BUSY: '작업 중', ERROR: '오류',
  OFFLINE: '오프라인', LOADING: '차량 인수 중', UNLOADING: '설비 인계 중' };

/** The rack drawn as it stands: level 1 at the bottom, so the picture matches the shelf. */
function SlotGrid({ slots, columns, onToggle }:
  { slots: SlotView[]; columns: number; onToggle?: (s: SlotView) => void }) {
  const levels = [...new Set(slots.map(s => s.level))].sort((a, b) => b - a);
  return (
    <div style={{ display: 'grid', gap: 8 }}>
      {levels.map(level => (
        <div key={level} style={{ display: 'flex', gap: 5, alignItems: 'center' }}>
          <span className="mono" style={{ fontSize: 10, color: 'var(--mut)', width: 26 }}>{level}단</span>
          <div style={{ display: 'grid', gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`, gap: 4, flex: 1 }}>
            {slots.filter(s => s.level === level).sort((a, b) => a.col - b.col).map(s => (
              <button key={s.id} type="button" disabled={!onToggle} onClick={() => onToggle?.(s)}
                title={`${s.code} · ${s.col}열 ${s.level}단${s.usable ? '' : ` · 사용 금지 — ${s.unusableReason ?? ''}`}`}
                style={{
                  border: `1px solid ${s.usable ? 'var(--ln2)' : 'var(--warn)'}`,
                  background: s.usable ? 'var(--srf)' : '#FBF0DA',
                  color: s.usable ? 'var(--ink2)' : '#8A5C05',
                  borderRadius: 4, padding: '6px 2px', fontSize: 10, fontFamily: 'var(--mono)',
                  cursor: onToggle ? 'pointer' : 'default', overflow: 'hidden', textOverflow: 'ellipsis',
                }}>
                {s.col}
              </button>
            ))}
          </div>
        </div>
      ))}
      <p className="hint" style={{ margin: 0 }}>
        셀을 누르면 사용 금지로 표시하거나 해제합니다. 금지할 때는 사유를 적어야 합니다 — 왜 비워 두는지가
        남지 않으면 나중에 그 칸을 다시 쓸 수 있는지 판단할 수 없습니다.
      </p>
    </div>
  );
}

const KIND: Record<string, string> = { STOCKER: '스토커', BUFFER: '버퍼', PROCESS: '공정' };
/** A cancelled request carries no answer, so it must not be recorded as an empty one. */
export function aborted(cause: unknown): boolean { return (cause as Error)?.name === 'AbortError'; }
function explain(code: string): string {
  if (code === 'EQUIPMENT_PROHIBITED') return '사용금지·콜금지를 해제한 뒤 상태를 변경해 주세요.';
  if (code === 'SITE_NOT_IN_SIMULATION') return '시뮬레이션 모드에서만 가상 설비를 변경할 수 있습니다.';
  if (code === 'UNUSABLE_REASON_REQUIRED') return '사용 금지로 바꾸려면 사유가 필요합니다.';
  if (code === 'UNUSABLE_SLOT_WOULD_BE_REMOVED') return '줄이려는 범위에 사용 금지 셀이 있습니다. 그 표시가 사라지므로 거부했습니다.';
  if (code === 'SITE_NOT_IN_MAINTENANCE') return '유지보수 상태에서만 설비 설정을 바꿀 수 있습니다.';
  if (code === 'ROLE_ENGINEER_REQUIRED') return '설비 설정 권한이 필요합니다.';
  // Why work would be refused, as the server decides it. The same codes transport throws.
  if (code === 'EQUIPMENT_DISABLED') return '사용금지 상태입니다.';
  if (code === 'EQUIPMENT_CALL_DISABLED') return '호출을 받지 않는 상태입니다.';
  if (code === 'EQUIPMENT_IN_FAULT') return '설비가 오류를 보고했습니다.';
  if (code === 'EQUIPMENT_OFFLINE') return '연결 설정은 있으나 설비에게서 들은 것이 없습니다. 통신이 끊겼거나 아직 한 번도 수신하지 못했습니다.';
  return code;
}

/**
 * What each unit says, and whether work would be refused.
 *
 * <p>The link writes what the machine says and transport reads it to decide. Between those two
 * there was nowhere to look, so a carrier could be refused for a reason that existed only inside a
 * decision. The refusal shown here is the server's own answer rather than one worked out again,
 * because a screen saying "ready" while transport refuses the port is a screen nobody trusts twice.
 */
function EquipmentLinkState({ simulated }: { simulated: boolean }) {
  const [states, setStates] = React.useState<EquipmentState[] | null>(null);
  const [error, setError] = React.useState('');
  React.useEffect(() => {
    const c = new AbortController();
    let active = true;
    const read = () => get<EquipmentState[]>(equipmentApi.states(), c.signal)
      .then(v => { if (active) { setStates(v); setError(''); } })
      .catch(e => { if (active && !aborted(e)) setError('설비 상태를 읽지 못했습니다.'); });
    void read();
    const timer = window.setInterval(read, 2000);
    return () => { active = false; c.abort(); window.clearInterval(timer); };
  }, []);
  if (error) return <section className="card"><div className="cb"><p role="alert" style={{ margin: 0, fontSize: 12 }}>{error}</p></div></section>;
  if (!states || states.length === 0) return null;
  const wired = states.filter(s => s.wired).length;
  return <section className="card">
    <div className="ch"><h2>설비 상태</h2>
      <span className="aux">{simulated ? '시뮬레이션 값입니다 — 운전 모드가 OPERATION이면 설비가 말한 값으로 바뀝니다'
        : `설비가 링크로 말한 값입니다 · 연결 설정된 설비 ${wired}대`}</span>
    </div>
    <div className="cb">
      <Grid cols="130px 90px 90px 90px 90px 130px 1fr"
        head={['설비', '연결', '상태', '사용', '콜', '마지막 수신', '작업 가능 여부']}>
        {states.map(s => (
          <Row key={s.unitId}>
            <span className="mono">{s.code}</span>
            <span>{!s.wired
              ? <span className="hint" style={{ margin: 0 }}>미설정</span>
              : s.online === null ? <Chip tone="id">—</Chip>
                : <Chip tone={s.online ? 'ok' : 'er'}>{s.online ? '수신 중' : '끊김'}</Chip>}</span>
            <span>{s.status ? <Chip tone={STATUS_TONE[s.status] ?? 'id'}>{EQ_STATUS[s.status] ?? s.status}</Chip>
              : <span className="hint" style={{ margin: 0 }}>—</span>}</span>
            <span>{mark(s.configuredEnabled && s.available !== false)}</span>
            <span>{mark(s.callEnabled !== false)}</span>
            <span className="mono" style={{ fontSize: 11 }}>{s.lastSeenAt ? s.lastSeenAt.slice(11, 19) : '—'}</span>
            <span>{s.refusal
              ? <><Chip tone="er">거부</Chip> <span style={{ fontSize: 12 }}>{explain(s.refusal)}</span></>
              : <Chip tone="ok">가능</Chip>}</span>
          </Row>
        ))}
      </Grid>
      {!simulated && wired === 0 && <p className="hint" style={{ marginTop: 8 }}>
        연결 설정된 설비가 없습니다. 설비가 말하는 것을 받으려면 자원 구성의 <b>설비 통신</b>에서 설비마다
        연결·프로토콜 프로파일을 지정하세요. 설정하지 않은 설비는 거부하지 않고 그대로 통과시킵니다.
      </p>}
    </div>
  </section>;
}
/**
 * 설비에 알린 것.
 *
 * <p>관제는 그 포트로 차량을 보냈다는 것을 알고 설비는 모릅니다. 미리 알면 설비가 포트를 잡아 두고
 * 반송물을 준비할 수 있으므로, 그 한 가지를 알립니다 — 인수도 핸드셰이크 자체는 차량과 포트가 자기들
 * 선으로 하는 일이고 관제는 그 사이에 끼지 않습니다.
 *
 * <p>여기가 **전달 여부를 볼 수 있는 유일한 자리**입니다. `결과 불명`인 포트는 설비가 알 수도 있고
 * 모를 수도 있는 포트이고, 다른 화면에는 그런 사실이 나타나지 않습니다.
 */
function EquipmentStatements() {
  const [told, setTold] = React.useState<EquipmentStatement[] | null>(null);
  React.useEffect(() => {
    const c = new AbortController();
    let active = true;
    const read = () => get<EquipmentStatement[]>(equipmentApi.statements(), c.signal)
      .then(v => { if (active) setTold(v); })
      .catch(() => { /* 설비 상태 패널이 이미 링크 오류를 말합니다 */ });
    void read();
    const timer = window.setInterval(read, 2000);
    return () => { active = false; c.abort(); window.clearInterval(timer); };
  }, []);
  if (!told || told.length === 0) return null;
  return <section className="card">
    <div className="ch"><h2>설비에 알린 것</h2>
      <span className="aux">그 포트로 반송이 가고 있다는 예고입니다 · 인수도 핸드셰이크는 차량과 포트가 합니다</span>
    </div>
    <div className="cb">
      <Grid cols="120px 110px 90px 90px 120px 110px 1fr"
        head={['포트', '설비', '예고', '방향', '반송물', '전달', '비고']}>
        {told.map(t => (
          <Row key={t.portId}>
            <span className="mono">{t.portCode}</span>
            <span className="mono">{t.unitCode}</span>
            <span>{t.reserved ? <Chip tone={t.transferring ? 'wr' : 'ok'}>{t.transferring ? '인수도 중' : '예약'}</Chip>
              : <span className="hint" style={{ margin: 0 }}>없음</span>}</span>
            <span>{t.direction ? (t.direction === 'PICKUP' ? '설비 반출' : '설비 반입') : '—'}</span>
            <span className="mono" style={{ fontSize: 11 }}>{t.carrier ?? '—'}</span>
            <span><Chip tone={STATEMENT_TONE[t.state]}>{STATEMENT_STATE[t.state]}</Chip></span>
            <span style={{ fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
              title={t.lastError ?? ''}>
              {t.attention ? '사람 확인 대기 · ' : ''}{t.lastError ?? (t.toldAt ? t.toldAt.slice(11, 19) : '')}
            </span>
          </Row>
        ))}
      </Grid>
    </div>
  </section>;
}
/** 전달·결과 불명·미전달을 나눕니다 — "알렸다"와 "알았는지 모른다"는 다른 사실입니다. */
const STATEMENT_STATE: Record<EquipmentStatement['state'], string> = {
  TOLD: '전달', IN_DOUBT: '결과 불명', NOT_TOLD: '미전달',
};
const STATEMENT_TONE: Record<EquipmentStatement['state'], string> = {
  TOLD: 'ok', IN_DOUBT: 'wr', NOT_TOLD: 'er',
};
const EQ_STATUS: Record<string, string> = { IDLE: '대기', READY: '준비', BUSY: '작업 중', ERROR: '오류',
  OFFLINE: '오프라인', LOADING: '차량 인수 중', UNLOADING: '설비 인계 중' };
const STATUS_TONE: Record<string, string> = { READY: 'ok', IDLE: 'id', BUSY: 'wr', LOADING: 'wr',
  UNLOADING: 'wr', ERROR: 'er', OFFLINE: 'er' };
function mark(ok: boolean) {
  return <span style={{ color: ok ? 'var(--ok)' : 'var(--crit)', fontWeight: 600 }}>{ok ? '○' : '✕'}</span>;
}
