import React from 'react';
import {
  ApiError, get, opsApi, send,
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
export function EquipmentScreen({ session, revisionId }: { session: Session; revisionId: number }) {
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
      .catch(() => setUnits([]));
    return () => c.abort();
  }, [reload]);

  React.useEffect(() => {
    if (unitId === null) { setRacks([]); setRackId(null); return; }
    const c = new AbortController();
    get<Cfg<RackSettings>[]>(opsApi.racks(unitId), c.signal)
      .then(list => { setRacks(list); setRackId(list[0]?.id ?? null); })
      .catch(() => setRacks([]));
    return () => c.abort();
  }, [unitId, reload]);

  React.useEffect(() => {
    if (unitId === null || rackId === null) { setSlots([]); return; }
    const c = new AbortController();
    get<SlotView[]>(opsApi.slots(unitId, rackId), c.signal).then(setSlots).catch(() => setSlots([]));
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
                title={s.usable ? s.code : `${s.code} · 사용 금지 — ${s.unusableReason ?? ''}`}
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
function explain(code: string): string {
  if (code === 'UNUSABLE_REASON_REQUIRED') return '사용 금지로 바꾸려면 사유가 필요합니다.';
  if (code === 'UNUSABLE_SLOT_WOULD_BE_REMOVED') return '줄이려는 범위에 사용 금지 셀이 있습니다. 그 표시가 사라지므로 거부했습니다.';
  if (code === 'SITE_NOT_IN_MAINTENANCE') return '유지보수 상태에서만 설비 설정을 바꿀 수 있습니다.';
  if (code === 'ROLE_ENGINEER_REQUIRED') return '설비 설정 권한이 필요합니다.';
  return code;
}
