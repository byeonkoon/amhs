import React from 'react';
import {
  canOperate, opsApi, send, ApiError, type Deadlock, type DeadlockPlan, type DeadlockRecovery, type MapData, type NodeHold,
  type VehicleSnapshot, type ZoneOccupancy,
} from '../../api';
import { Chip, Empty } from '../../shell/States';

const RECOVERY_LABEL: Record<DeadlockRecovery['status'], { label: string; tone: string }> = {
  STOPPING: { label: '정지 확인 중', tone: 'wr' },
  YIELDING: { label: '양보 중', tone: 'ac' },
  DONE: { label: '완료', tone: 'ok' },
  ABANDONED: { label: '취소', tone: 'id' },
};
const FINISH_LABEL: Record<string, string> = {
  BENEFICIARY_TOOK_NODE: '상대 차량이 노드를 확보함',
  NODE_TAKEN_BY_OTHER: '다른 차량이 노드를 확보함',
  BENEFICIARY_NOT_WAITING: '상대 차량이 더 이상 기다리지 않음',
  NO_LONGER_WAITED_FOR: '교착이 스스로 풀림',
  OPERATOR_CLOSED: '운전자가 취소함',
};
const REJECT_LABEL: Record<string, string> = {
  GIVING_WAY_WOULD_NOT_HELP: '이 차량이 양보해도 풀리지 않습니다. 기다리는 노드에 차량이 서 있으면 수동으로 옮겨야 합니다.',
  NOT_IN_DEADLOCK: '이 차량은 지금 교착 사슬에 없습니다.',
  RECOVERY_ALREADY_OPEN: '이 사슬은 이미 다른 차량이 양보 중입니다.',
  REASON_REQUIRED: '사유를 입력해 주세요.',
  VEHICLE_NOT_CONTROLLABLE: '차량이 오프라인이거나 원격 제어 상태가 아닙니다.',
  POSITION_UNKNOWN: '차량 위치를 알 수 없습니다.',
};

/**
 * Who may occupy what, and which vehicles cannot get out of each other's way.
 *
 * A run is shown as the chain of nodes it covers, in the order it was granted, because that is
 * what a reservation is: permission to be somewhere, starting where the vehicle stands.
 */
export function TrafficScreen(props: {
  map: MapData; holds: NodeHold[]; deadlocks: Deadlock[]; vehicles: VehicleSnapshot[];
  zones: ZoneOccupancy[]; plans?: DeadlockPlan[]; recoveries?: DeadlockRecovery[]; onChanged?: () => void;
}) {
  const nodeCode = (id: number) => props.map.nodes.find(n => n.id === id)?.code ?? String(id);
  const vehicleCode = (id: number) => props.vehicles.find(v => v.id === id)?.code ?? `#${id}`;
  const position = (id: number) => props.vehicles.find(v => v.id === id)?.nodeId ?? null;

  const byVehicle = new Map<number, NodeHold[]>();
  for (const hold of props.holds) {
    const run = byVehicle.get(hold.vehicleId) ?? [];
    run.push(hold);
    byVehicle.set(hold.vehicleId, run);
  }
  for (const run of byVehicle.values()) run.sort((a, b) => a.sequence - b.sequence);

  return (
    <>
      <div className="grid g4">
        <Kpi k="점유 노드" v={props.holds.length} d={`전체 ${props.map.nodes.length}개 중`} />
        <Kpi k="점유 차량" v={byVehicle.size} d="허가를 보유한 차량" />
        <Kpi k="교착 사슬" v={props.deadlocks.length} d={props.deadlocks.length ? '조치 필요' : '없음'} />
        <Kpi k="가용 노드" v={props.map.nodes.filter(n => n.enabled).length - props.holds.length} d="사용 가능·미점유" />
      </div>

      <section className="card">
        <div className="ch">
          <h2>교착 사슬</h2>
          <span className="aux">서로가 가진 노드를 기다려 스스로 빠져나올 수 없는 상태입니다</span>
        </div>
        {props.deadlocks.length === 0
          ? <Empty title="교착이 없습니다"
              hint="대기 중인 차량이 있어도 사슬이 닫히지 않으면 교착이 아닙니다. 줄 서서 기다리는 것은 혼잡입니다." />
          : (
            <div className="cb" style={{ display: 'grid', gap: 10 }}>
              {props.deadlocks.map((d, i) => (
                <div key={i} style={{ border: '1px solid var(--crit)', borderRadius: 6, padding: '10px 12px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                    <Chip tone="er">사슬 {i + 1}</Chip>
                    {d.vehicleIds.map((v, j) => (
                      <React.Fragment key={v}>
                        {j > 0 && <span style={{ color: 'var(--mut)' }}>→</span>}
                        <span className="mono">
                          {vehicleCode(v)}
                          <span style={{ color: 'var(--mut)' }}> ({nodeCode(d.wantedNodeIds[j])} 대기)</span>
                        </span>
                      </React.Fragment>
                    ))}
                    <span style={{ color: 'var(--mut)' }}>→ {vehicleCode(d.vehicleIds[0])}</span>
                  </div>
                  <RecoveryChoice plan={planOf(props.plans, d)} vehicleCode={vehicleCode} nodeCode={nodeCode}
                    onChanged={props.onChanged} />
                </div>
              ))}
            </div>
          )}
      </section>

      <RecoveryList recoveries={props.recoveries ?? []} vehicleCode={vehicleCode} nodeCode={nodeCode}
        onChanged={props.onChanged} />

      <section className="card">
        <div className="ch">
          <h2>Zone 정원</h2>
          <span className="aux">정원이 찬 Zone에는 들어가지 않고, 진입하려던 차량은 본선을 돌며 자리가 나기를 기다립니다</span>
        </div>
        {props.zones.length === 0
          ? <Empty title="정의된 Zone이 없습니다" hint="Zone은 진입 대수를 제한할 노드 묶음입니다." />
          : (
            <div className="tbl-scroll">
              <table className="t">
                <thead>
                  <tr><th>Zone</th><th>상태</th><th>안의 차량 / 정원</th><th>노드</th><th>안의 차량</th></tr>
                </thead>
                <tbody>
                  {props.zones.map(z => (
                    <tr key={z.id}>
                      <td className="mono">{z.code}</td>
                      <td>
                        {!z.enabled
                          ? <Chip tone="id">사용 안 함</Chip>
                          : z.vehicleIds.length >= z.capacity ? <Chip tone="wr">만석</Chip> : <Chip tone="ok">진입 가능</Chip>}
                      </td>
                      <td className="mono">{z.vehicleIds.length} / {z.capacity}</td>
                      <td className="mono">{z.nodeIds.map(nodeCode).join(', ')}</td>
                      <td className="mono">{z.vehicleIds.length ? z.vehicleIds.map(vehicleCode).join(', ') : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        <p className="hint" style={{ padding: '0 15px 14px' }}>
          안의 차량은 Zone 노드를 점유 허가로 쥐고 있거나 그 노드에 서 있다고 보고한 차량입니다. 이미 안에 있는
          차량이 Zone 안에서 움직이거나 빠져나가는 것은 막지 않습니다. 작업을 마치고 남은 차량은 실행 스윕이 Zone
          밖의 가장 가까운 빈 노드(합류 지점·포트 제외)로 보냅니다.
        </p>
      </section>

      <section className="card">
        <div className="ch">
          <h2>차량별 점유 구간</h2>
          <span className="aux">차량이 선 노드부터 연속된 구간만 부여되며, 지나간 노드는 도착 보고로만 반납됩니다</span>
        </div>
        {byVehicle.size === 0
          ? <Empty title="점유 중인 구간이 없습니다" hint="배차된 차량이 이동 허가를 받으면 여기에 표시됩니다." />
          : (
            <div className="tbl-scroll">
              <table className="t">
                <thead>
                  <tr><th>차량</th><th>작업</th><th>구간 수</th><th>점유 구간 (부여 순)</th></tr>
                </thead>
                <tbody>
                  {[...byVehicle.entries()].map(([vehicleId, run]) => {
                    const at = position(vehicleId);
                    return (
                      <tr key={vehicleId}>
                        <td className="mono">{vehicleCode(vehicleId)}</td>
                        <td className="mono">{run[0].orderId ? `#${run[0].orderId}` : '—'}</td>
                        <td className="mono">{run.length}</td>
                        <td>
                          <span style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                            {run.map((hold, i) => (
                              <React.Fragment key={hold.id}>
                                {i > 0 && <span style={{ color: 'var(--mut)' }}>›</span>}
                                {/* The node the vehicle reported standing on is marked so the rest reads as road ahead. */}
                                <Chip tone={hold.nodeId === at ? 'ac' : 'id'}>
                                  {nodeCode(hold.nodeId)}{hold.nodeId === at ? ' · 현재' : ''}
                                </Chip>
                              </React.Fragment>
                            ))}
                          </span>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        <p className="hint" style={{ padding: '0 15px 14px' }}>
          경로는 갈 수 있는 곳을, 점유는 있어도 되는 곳을 말합니다. 구간은 따로 예약하지 않으며, 양끝 노드를
          같은 차량이 쥐고 있을 때만 통과할 수 있어 마주 오는 차량은 같은 노드에서 걸립니다.
        </p>
      </section>
    </>
  );
}

function Kpi({ k, v, d }: { k: string; v: React.ReactNode; d: string }) {
  return <div className="kpi"><div className="k">{k}</div><div className="v">{v}</div><div className="d">{d}</div></div>;
}

/** The plan for a chain, matched by its vehicles (both lists are read separately). */
function planOf(plans: DeadlockPlan[] | undefined, d: Deadlock) {
  const key = [...d.vehicleIds].sort((a, b) => a - b).join(',');
  return plans?.find(p => [...p.vehicleIds].sort((a, b) => a - b).join(',') === key) ?? null;
}

/**
 * How this chain can be undone. A vehicle that only holds, ahead of where it stands, the node the
 * one before it waits for can give way: it is stopped, then gives back that road. Where every waited
 * node has a vehicle standing on it, nothing given back helps; a person moves a vehicle by hand.
 */
function RecoveryChoice(props: {
  plan: DeadlockPlan | null; vehicleCode: (id: number) => string; nodeCode: (id: number) => string; onChanged?: () => void;
}) {
  const [reason, setReason] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [message, setMessage] = React.useState<string | null>(null);
  const plan = props.plan;
  if (!plan) return <p className="hint" style={{ margin: '8px 0 0' }}>복구 방법을 읽는 중입니다.</p>;
  const candidates = [...new Set(plan.links.filter(l => l.releasable).map(l => l.blockerId))];
  const giveWay = async (vehicleId: number) => {
    setBusy(true); setMessage(null);
    try {
      await send(opsApi.deadlockRecoveries(), 'POST', { vehicleId }, reason.trim());
      setMessage(`${props.vehicleCode(vehicleId)}에 정지를 보냈습니다. 정지가 확인되면 앞 예약을 반납합니다.`);
      setReason('');
      props.onChanged?.();
    } catch (e) {
      const code = e instanceof ApiError ? e.message : '';
      setMessage(REJECT_LABEL[code] ?? (e as Error).message);
    } finally { setBusy(false); }
  };
  return (
    <div style={{ marginTop: 8, display: 'grid', gap: 6 }}>
      <ul style={{ margin: 0, paddingLeft: 18, fontSize: 12 }}>
        {plan.links.map(l => (
          <li key={l.vehicleId}>
            <span className="mono">{props.vehicleCode(l.vehicleId)}</span>이(가) <span className="mono">{props.nodeCode(l.wantedNodeId)}</span>를
            기다림 — <span className="mono">{props.vehicleCode(l.blockerId)}</span>
            {l.blockerStandsThere ? ' 차량이 서 있음' : l.releasable ? '의 앞 예약(양보로 해제 가능)' : '의 예약'}
          </li>
        ))}
      </ul>
      {plan.recoveryId != null
        ? <p className="hint" style={{ margin: 0 }}>복구 #{plan.recoveryId} 진행 중입니다. 아래 복구 목록에서 상태를 확인하세요.</p>
        : candidates.length === 0
          ? <p className="hint" style={{ margin: 0 }}>
              기다리는 노드마다 차량이 서 있어 예약 반납으로는 풀 수 없습니다. 현장에서 한 대를 수동 모드로 옮긴 뒤, 위치 보고가
              들어오면 사슬이 사라집니다. 제어기는 후진이나 강제 해제를 하지 않습니다.
            </p>
          : (
            <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
              <input value={reason} onChange={e => setReason(e.target.value)} placeholder="양보 사유 (필수)"
                style={{ flex: '1 1 180px', minWidth: 140 }} disabled={busy} />
              {candidates.map(v => (
                <button key={v} type="button" disabled={busy || !reason.trim() || !canOperate()}
                  title={canOperate() ? '정지시킨 뒤 앞 예약만 반납하고, 상대가 지나갈 때까지 새 예약을 받지 않습니다.' : 'Lv2 운전 이상으로 로그인해야 합니다.'}
                  onClick={() => giveWay(v)}>{props.vehicleCode(v)} 양보</button>
              ))}
            </div>
          )}
      {message && <p className="hint" style={{ margin: 0 }}>{message}</p>}
    </div>
  );
}

/** Recoveries under way and the most recent finished ones. */
function RecoveryList(props: {
  recoveries: DeadlockRecovery[]; vehicleCode: (id: number) => string; nodeCode: (id: number) => string; onChanged?: () => void;
}) {
  const [busy, setBusy] = React.useState<number | null>(null);
  const close = async (id: number) => {
    setBusy(id);
    try { await send(opsApi.closeRecovery(id), 'POST', {}, '교착 복구 취소'); props.onChanged?.(); }
    catch { /* the list shows the state as it is */ }
    finally { setBusy(null); }
  };
  const time = (t: string | null) => {
    if (!t) return '—';
    const d = new Date(t), two = (n: number) => String(n).padStart(2, '0');
    return `${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}`;
  };
  return (
    <section className="card">
      <div className="ch">
        <h2>교착 복구</h2>
        <span className="aux">양보 차량은 정지가 확인된 뒤에만 앞 예약을 반납하고, 상대가 노드를 확보할 때까지 새 예약을 받지 않습니다</span>
      </div>
      {props.recoveries.length === 0
        ? <Empty title="복구 기록이 없습니다" hint="교착 사슬에서 양보할 차량을 고르면 여기에 진행 상태가 나옵니다." />
        : (
          <div className="tbl-scroll">
            <table className="t">
              <thead><tr><th>#</th><th>양보 차량</th><th>상대</th><th>노드</th><th>상태</th><th>반납</th><th>사유</th><th>요청</th><th></th></tr></thead>
              <tbody>
                {props.recoveries.map(r => (
                  <tr key={r.id}>
                    <td className="mono">{r.id}</td>
                    <td className="mono">{props.vehicleCode(r.vehicleId)}</td>
                    <td className="mono">{props.vehicleCode(r.beneficiaryVehicleId)}</td>
                    <td className="mono">{props.nodeCode(r.wantedNodeId)}</td>
                    <td><Chip tone={RECOVERY_LABEL[r.status].tone}>{RECOVERY_LABEL[r.status].label}</Chip>
                      {r.finishReason && <span className="hint"> {FINISH_LABEL[r.finishReason] ?? r.finishReason}</span>}</td>
                    <td className="mono">{r.releasedNodeIds.length ? r.releasedNodeIds.map(props.nodeCode).join(', ') : '—'}</td>
                    <td>{r.reason}</td>
                    <td className="mono">{r.requestedBy} {time(r.requestedAt)}</td>
                    <td>{r.finishedAt == null &&
                      <button type="button" disabled={busy === r.id || !canOperate()} onClick={() => close(r.id)}>취소</button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
    </section>
  );
}
