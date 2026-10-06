import React from 'react';
import { ApiError, opsApi, send, type RestartCheckItem, type RestartCheckState } from '../../api';
import { Chip } from '../../shell/States';

const KIND: Record<RestartCheckItem['kind'], string> = {
  VEHICLE_SILENT: '재시작 뒤 보고 없음',
  POSITION_UNKNOWN: '위치 불명',
  VEHICLE_OFF_ROAD: '점유 경로 밖에서 보고',
  COMMAND_IN_DOUBT: '결과 불명 명령',
  VEHICLE_CONFIRMED: '정지 확인',
  VEHICLE_MOVING: '주행 중 · 점유 유지',
  VEHICLE_ISOLATED: '격리됨',
  COMMAND_UNSENT: '미송신 명령 · 확인 시 철회',
  ORDER_WAITS_FOR_OPERATOR: '운전자 확인 대기 작업',
};
const ADVICE: Partial<Record<RestartCheckItem['kind'], string>> = {
  VEHICLE_SILENT: '차량이 보고해야 합니다. 응답이 없으면 서비스 제외로 격리합니다.',
  POSITION_UNKNOWN: '위치가 보고되어야 합니다.',
  VEHICLE_OFF_ROAD: '서버가 멈춘 사이 다른 곳으로 옮겨졌습니다. 현장 확인 후 차량을 다시 배치하거나 서비스 제외로 격리합니다.',
  COMMAND_IN_DOUBT: '차량이 정지한 것이 보고된 뒤에만 닫을 수 있습니다. 닫으면 "받았을 수 있음"으로 기록합니다.',
};
const REJECT: Record<string, string> = {
  RESTART_CHECK_UNRESOLVED: '아직 해결되지 않은 항목이 있습니다.',
  REASON_REQUIRED: '확인한 내용을 적어 주세요.',
  VEHICLE_NOT_SEEN_STANDING: '재시작 뒤 이 차량이 정지한 것이 보고되지 않았습니다.',
  COMMAND_NOT_LOST_IN_RESTART: '재시작 전에 나간 결과 불명 명령이 아닙니다.',
};

/**
 * The comparison the site waits for after the server started with work in progress. Nothing
 * progresses until an operator confirms it, and that is refused while anything blocking is left.
 */
export function RestartCheckPanel(props: {
  check: RestartCheckState; vehicleCode: (id: number | null) => string; nodeCode: (id: number | null) => string;
  canOperate: boolean; onChanged: () => void;
}) {
  const [reason, setReason] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [message, setMessage] = React.useState<string | null>(null);
  const items = [...props.check.items].sort((a, b) => Number(b.blocking) - Number(a.blocking));
  const blocking = items.filter(i => i.blocking).length;
  const act = async (url: string, done: string) => {
    setBusy(true); setMessage(null);
    try { await send(url, 'POST', {}, reason.trim()); setMessage(done); props.onChanged(); }
    catch (e) {
      const code = e instanceof ApiError ? e.message : '';
      setMessage(REJECT[code] ?? (e as Error).message);
    } finally { setBusy(false); }
  };
  const since = props.check.since ? new Date(props.check.since).toLocaleString('ko-KR', { hour12: false }) : '—';
  return (
    <section className="card" style={{ borderColor: '#C77700' }}>
      <div className="ch">
        <h2>재시작 대조</h2>
        <span className="aux">서버가 {since}에 다시 시작될 때 진행 중이던 작업이 있습니다. 차량 상태와 대조해 확인하기 전까지 배차·새 이동 허가·명령 전송을 하지 않습니다</span>
      </div>
      <div className="cb" style={{ display: 'grid', gap: 8 }}>
        <div className="tbl-scroll">
          <table className="t">
            <thead><tr><th>구분</th><th>차량</th><th>작업</th><th>명령</th><th>노드</th><th>조치</th></tr></thead>
            <tbody>
              {items.map((i, n) => (
                <tr key={n}>
                  <td><Chip tone={i.blocking ? 'er' : i.kind === 'VEHICLE_CONFIRMED' ? 'ok' : 'id'}>{KIND[i.kind]}</Chip></td>
                  <td className="mono">{i.vehicleId == null ? '—' : props.vehicleCode(i.vehicleId)}</td>
                  <td className="mono">{i.orderId ?? '—'}</td>
                  <td className="mono">{i.commandId ? `${i.detail ?? ''}` : '—'}</td>
                  <td className="mono">{i.nodeId == null ? '—' : props.nodeCode(i.nodeId)}</td>
                  <td>
                    {i.kind === 'COMMAND_IN_DOUBT' && i.commandId
                      ? <button type="button" disabled={busy || !props.canOperate || !reason.trim()}
                          onClick={() => act(opsApi.restartCheckClose(i.commandId!), '명령을 닫았습니다.')}>명령 닫기</button>
                      : <span className="hint">{i.detail === 'ROAD_AHEAD_GIVEN_BACK_ON_CONFIRM'
                        ? '확인하면 서 있는 노드 앞의 예약을 반납하고, 작업은 이 위치에서 다시 허가를 받습니다.'
                        : i.kind === 'VEHICLE_ISOLATED' ? (i.detail === 'OUT_OF_SERVICE' ? '서비스 제외 상태로 격리되어 있습니다.' : '사용 안 함 상태입니다.')
                        : ADVICE[i.kind] ?? ''}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
          <input value={reason} onChange={e => setReason(e.target.value)} placeholder="확인한 내용 (필수)"
            style={{ flex: '1 1 220px', minWidth: 160 }} disabled={busy || !props.canOperate} />
          <button type="button" disabled={busy || !props.canOperate || !reason.trim() || !props.check.confirmable}
            title={props.check.confirmable ? '정지가 확인된 차량은 서 있는 노드 앞의 예약을 반납하고, 재시작 전 미송신 명령을 철회한 뒤 진행합니다.'
              : `해결되지 않은 항목 ${blocking}건`}
            onClick={() => act(opsApi.restartCheckConfirm(), '대조를 확인했습니다. 작업이 다시 진행됩니다.')}>
            대조 완료 · 진행 재개
          </button>
          {!props.canOperate && <span className="hint">Lv2 운전 이상 로그인이 필요합니다.</span>}
          {blocking > 0 && <span className="hint">해결되지 않은 항목 {blocking}건</span>}
        </div>
        {message && <p className="hint" style={{ margin: 0 }}>{message}</p>}
      </div>
    </section>
  );
}
