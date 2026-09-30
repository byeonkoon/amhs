import React from 'react';
import { type Deadlock, type MapData, type NodeHold, type VehicleSnapshot } from '../../api';
import { Chip, Empty } from '../../shell/States';

/**
 * Who may occupy what, and which vehicles cannot get out of each other's way.
 *
 * A run is shown as the chain of nodes it covers, in the order it was granted, because that is
 * what a reservation is: permission to be somewhere, starting where the vehicle stands.
 */
export function TrafficScreen(props: {
  map: MapData; holds: NodeHold[]; deadlocks: Deadlock[]; vehicles: VehicleSnapshot[];
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
                  <p className="hint" style={{ margin: '8px 0 0' }}>
                    제어기는 이 상태를 자동으로 풀지 않습니다. 한 대를 물러나게 해야 하고, 후진은 별도의 이동
                    명령과 안전 판단이 필요한 물리 동작이기 때문입니다.
                  </p>
                </div>
              ))}
            </div>
          )}
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
