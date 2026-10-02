import React from 'react';
import { MODE_LABEL, STATUS_LABEL, type MapData, type VehicleSnapshot } from '../../api';
import { portTitle, segmentPath, vehiclePlaces, vehiclePoint } from './geometry';
import { useSmoothPositions } from './motion';

export const OHT = '#1D6FD8';
export const AGV = '#9A4A9E';
const PAD = 900;
const MIN_SCALE = 1, MAX_SCALE = 12;

/**
 * Vehicle body colours, as the existing system paints them: idle, moving (also while extended),
 * alarm, pause, charge, load and unload, manual, and a lost connection.
 */
export const VEHICLE_COLOR = {
  IDLE: '#D9D25B', MOVE: '#228B22', ALARM: '#FF0000', PAUSE: '#C0C0C0', CHARGE: '#FF00FF',
  LOAD: '#17F9F9', MANUAL: '#FF8000', DISCONNECTED: '#666463',
} as const;
/** Outline: green while the vehicle may be given work (callable), grey while it may not. */
export const CALL_ON = '#16A34A', CALL_OFF = '#9AA5B1';
/** Carrier mark: green with a carrier on board, the inactive colour without. */
export const CARRIER_ON = '#22C55E', CARRIER_OFF = '#E0E3ED';

/**
 * The body colour and what it means. Under manual (or semi-automatic) control a vehicle is shown
 * as manual, or as an alarm when it reports one (grey if it is also disconnected). Under remote
 * control a disconnected vehicle is grey; otherwise the colour follows its status. RESET has no
 * colour of its own in the existing system and is shown like a pause.
 */
export function vehicleLook(v: VehicleSnapshot): { fill: string; label: string } {
  const mode = v.mode ?? (v.remote ? 'REMOTE' : 'MANUAL');
  const status = v.status ?? (v.idle ? 'IDLE' : 'MOVING');
  if (mode !== 'REMOTE') {
    if (status === 'ERROR') return v.online ? { fill: VEHICLE_COLOR.ALARM, label: '알람' } : { fill: VEHICLE_COLOR.DISCONNECTED, label: '연결 끊김' };
    return { fill: VEHICLE_COLOR.MANUAL, label: mode === 'SEMI_AUTO' ? '반자동' : '수동' };
  }
  if (!v.online) return { fill: VEHICLE_COLOR.DISCONNECTED, label: '연결 끊김' };
  switch (status) {
    case 'IDLE': return { fill: VEHICLE_COLOR.IDLE, label: '대기' };
    case 'MOVING': return { fill: VEHICLE_COLOR.MOVE, label: '이동' };
    case 'ERROR': return { fill: VEHICLE_COLOR.ALARM, label: '알람' };
    case 'PAUSED': return { fill: VEHICLE_COLOR.PAUSE, label: '일시정지' };
    case 'RESET': return { fill: VEHICLE_COLOR.PAUSE, label: '리셋' };
    case 'CHARGING': return { fill: VEHICLE_COLOR.CHARGE, label: '충전' };
    case 'LOADING': return { fill: VEHICLE_COLOR.LOAD, label: '적재' };
    case 'UNLOADING': return { fill: VEHICLE_COLOR.LOAD, label: '하역' };
    default: return { fill: VEHICLE_COLOR.PAUSE, label: status };
  }
}
/** Whether work may be given to it: in use and allowed to take work. */
export const callable = (v: VehicleSnapshot) => v.enabled && v.assignmentEnabled;

/** Status colours: what an operator has to notice first is a fault, then a stop, then work. */
export const STATUS_COLOR: Record<string, string> = {
  ERROR: '#C62828', PAUSED: '#E0A100', RESET: '#E0A100', CHARGING: '#2E7D32',
  LOADING: '#EF6C00', UNLOADING: '#EF6C00', MOVING: '#0B7A75', IDLE: '#5B6B7C',
};

type View = { x: number; y: number; w: number; h: number };

/** Which layer a node belongs to: its port's access type, else the code prefix. Shared by the 2D and 3D views. */
export function layerOfNode(map: MapData, id: number): 'OHT' | 'AGV' {
  const port = map.ports.find(p => p.linkedNodeId === id);
  if (port) return port.accessVehicleType;
  return map.nodes.find(n => n.id === id)?.code.startsWith('AGV') ? 'AGV' : 'OHT';
}

/**
 * Layout map. OHT and AGV are drawn as separate layers: solid line for the overhead rail,
 * dashed for the floor route, and different vehicle shapes. They are never joined by an edge,
 * because the graphs themselves are not connected.
 *
 * Vehicles are drawn on the segment, at the point they report, turned the way they travel: the
 * body colour is their state as the existing system paints it, the outline says whether they may
 * be given work (green) or not (grey), the round mark in the middle whether they carry (green) or
 * not, and the label their code and mode (R remote, S semi-auto, M manual). Positions are replayed
 * smoothly from the reported track. Segments may be curved. A port is named by its equipment. Nodes a
 * vehicle waits at (대기) or charges at (충전) carry a tag. The wheel zooms around the pointer,
 * dragging pans, and a node can be picked as a target by clicking it.
 */
export function MapView(props: {
  map: MapData;
  vehicles: VehicleSnapshot[];
  selectedId: number | null;
  onSelect: (vehicle: VehicleSnapshot) => void;
  layer: 'all' | 'OHT' | 'AGV';
  targetNodeId?: number | null;
  onNodeSelect?: (nodeId: number) => void;
}) {
  const { map, vehicles, layer } = props;
  const node = (id: number) => map.nodes.find(n => n.id === id);
  const typeOfNode = (id: number) => layerOfNode(map, id);
  const visible = (t: 'OHT' | 'AGV') => layer === 'all' || layer === t;
  // Drawn from the reported track replayed just behind real time, every frame.
  const smooth = useSmoothPositions(map, vehicles);

  const xs = map.nodes.map(n => n.x), ys = map.nodes.map(n => n.y);
  const full: View = React.useMemo(() => {
    const minX = Math.min(...xs) - PAD, maxX = Math.max(...xs) + PAD;
    const minY = Math.min(...ys) - PAD, maxY = Math.max(...ys) + PAD;
    return { x: minX, y: minY, w: maxX - minX, h: maxY - minY };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map.revisionId, map.nodes.length]);
  const [view, setView] = React.useState<View>(full);
  React.useEffect(() => setView(full), [full]);
  const svg = React.useRef<SVGSVGElement | null>(null);
  const drag = React.useRef<{ x: number; y: number; view: View; moved: boolean } | null>(null);
  const scale = full.w / view.w;

  /** Zooms by factor around a point given in map coordinates, keeping that point under the pointer. */
  const zoomAt = (factor: number, cx: number, cy: number) => setView(v => {
    const next = Math.min(MAX_SCALE, Math.max(MIN_SCALE, (full.w / v.w) * factor));
    const w = full.w / next, h = full.h / next;
    return { x: cx - (cx - v.x) * (w / v.w), y: cy - (cy - v.y) * (h / v.h), w, h };
  });
  const toMap = (clientX: number, clientY: number) => {
    const r = svg.current!.getBoundingClientRect();
    return { x: view.x + ((clientX - r.left) / r.width) * view.w, y: view.y + ((clientY - r.top) / r.height) * view.h };
  };

  React.useEffect(() => {
    const el = svg.current;
    if (!el) return;
    // Registered by hand because React's wheel listener is passive and could not stop the page scrolling.
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const p = toMap(e.clientX, e.clientY);
      zoomAt(e.deltaY < 0 ? 1.25 : 0.8, p.x, p.y);
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  });

  const onPointerDown = (e: React.PointerEvent) => {
    drag.current = { x: e.clientX, y: e.clientY, view, moved: false };
  };
  const onPointerMove = (e: React.PointerEvent) => {
    const d = drag.current;
    if (!d || !svg.current) return;
    const r = svg.current.getBoundingClientRect();
    const dx = (e.clientX - d.x) / r.width * d.view.w, dy = (e.clientY - d.y) / r.height * d.view.h;
    if (Math.abs(e.clientX - d.x) + Math.abs(e.clientY - d.y) > 3) d.moved = true;
    if (d.moved) setView({ ...d.view, x: d.view.x - dx, y: d.view.y - dy });
  };
  const endDrag = () => { setTimeout(() => { drag.current = null; }, 0); };
  /** A click that ended a drag is not a click on what happens to be under the pointer. */
  const clicked = () => !drag.current?.moved;

  const zoomButton = (label: string, title: string, run: () => void) => (
    <button type="button" title={title} aria-label={title} onClick={run}
      style={{ width: 28, height: 26, border: '1px solid var(--ln2)', background: 'var(--srf)', color: 'var(--ink2)',
        borderRadius: 5, cursor: 'pointer', fontSize: 13, lineHeight: 1 }}>{label}</button>
  );

  return (
    <div className="mapwrap" style={{ position: 'relative' }}>
      <div style={{ position: 'absolute', right: 10, top: 10, display: 'flex', gap: 4, zIndex: 2, alignItems: 'center' }}>
        <span className="mono" style={{ fontSize: 10.5, color: 'var(--mut)', marginRight: 4 }}>×{scale.toFixed(1)}</span>
        {zoomButton('+', '확대', () => zoomAt(1.4, view.x + view.w / 2, view.y + view.h / 2))}
        {zoomButton('−', '축소', () => zoomAt(1 / 1.4, view.x + view.w / 2, view.y + view.h / 2))}
        {zoomButton('⤢', '전체 보기', () => setView(full))}
      </div>
      <svg ref={svg} viewBox={`${view.x} ${view.y} ${view.w} ${view.h}`} role="img"
        aria-label="OHT 천장 레일과 AGV 바닥 주행로가 분리된 설비 지도. 휠로 확대·축소, 끌어서 이동"
        style={{ cursor: drag.current?.moved ? 'grabbing' : 'grab', touchAction: 'none' }}
        onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={endDrag} onPointerLeave={endDrag}>
        {map.edges.map(e => {
          const a = node(e.fromNodeId), b = node(e.toNodeId);
          if (!a || !b) return null;
          const t = typeOfNode(e.fromNodeId);
          if (!visible(t)) return null;
          return <path key={e.id} d={segmentPath(a.x, a.y, b.x, b.y, e.curveMm ?? 0)} fill="none"
            stroke={t === 'OHT' ? OHT : AGV} strokeWidth={34} strokeLinecap="round"
            strokeDasharray={t === 'OHT' ? undefined : '110 70'}
            opacity={e.enabled ? 1 : 0.35} />;
        })}

        {map.nodes.map(n => {
          const t = typeOfNode(n.id);
          if (!visible(t)) return null;
          const target = props.targetNodeId === n.id;
          return (
            <g key={n.id} style={{ cursor: props.onNodeSelect ? 'pointer' : undefined }}
              onClick={() => { if (props.onNodeSelect && clicked()) props.onNodeSelect(n.id); }}>
              {target && <circle cx={n.x} cy={n.y} r={150} fill="none" stroke="#E0A100" strokeWidth={30} strokeDasharray="60 30" />}
              <circle cx={n.x} cy={n.y} r={62} fill={n.enabled ? '#fff' : '#c4c4c4'} stroke={t === 'OHT' ? OHT : AGV} strokeWidth={18}>
                <title>{n.enabled ? n.code : `${n.code} · 사용 금지`}{props.onNodeSelect ? ' · 클릭하면 이동 목표로 선택' : ''}</title>
              </circle>
              <text x={n.x} y={n.y + 250} textAnchor="middle" fontSize={165}>{n.code}</text>
            </g>
          );
        })}

        {map.ports.map(p => {
          const n = node(p.linkedNodeId);
          if (!n || !visible(p.accessVehicleType)) return null;
          const title = portTitle(p);
          const w = Math.max(840, title.main.length * 175 + 160);
          return (
            <g key={p.id} pointerEvents="none">
              <title>{title.sub ? `${title.main} (${title.sub})` : title.main}</title>
              <rect x={n.x - w / 2} y={n.y + 380} width={w} height={title.sub ? 420 : 300} rx={50}
                fill="#fff" stroke={p.accessVehicleType === 'OHT' ? OHT : AGV} strokeWidth={16} />
              <text x={n.x} y={n.y + 580} textAnchor="middle" fontSize={165} fontWeight={700}
                style={{ fill: 'var(--ink)' }}>{title.main}</text>
              {title.sub && <text x={n.x} y={n.y + 735} textAnchor="middle" fontSize={120}>{title.sub}</text>}
            </g>
          );
        })}

        {/* Where vehicles wait (대기) and charge (충전), as configured per vehicle. */}
        {(() => {
          const places = vehiclePlaces(vehicles, map);
          const tag = (nodeId: number, label: string, color: string, who: string[], dx: number) => {
            const n = node(nodeId);
            if (!n || !visible(typeOfNode(nodeId))) return null;
            return (
              <g key={`${label}-${nodeId}`} pointerEvents="none">
                <title>{`${label} 장소${who.length ? ' · ' + who.join(', ') : ''}`}</title>
                <rect x={n.x + dx} y={n.y - 120} width={330} height={190} rx={40} fill={color} />
                <text x={n.x + dx + 165} y={n.y + 22} textAnchor="middle" fontSize={120} fontWeight={700}
                  style={{ fill: '#fff' }}>{label}</text>
              </g>
            );
          };
          return [
            ...[...places.home].map(([id, who]) => tag(id, '대기', '#5B6B7C', who, 120)),
            ...[...places.charge].map(([id, who]) => tag(id, '충전', '#2E7D32', who, places.home.has(id) ? 480 : 120)),
          ];
        })()}

        {vehicles.map(v => {
          const drawn = smooth.get(v.id);
          const at = drawn ?? vehiclePoint(map, v);
          if (!at || !visible(v.vType)) return null;
          const heading = drawn?.heading ?? 0;
          const selected = props.selectedId === v.id;
          const status = v.status ?? (v.idle ? 'IDLE' : 'MOVING');
          const look = vehicleLook(v);
          const call = callable(v);
          const mode = v.mode === 'REMOTE' ? 'R' : v.mode === 'SEMI_AUTO' ? 'S' : v.mode === 'MANUAL' ? 'M' : '?';
          const title = `${v.code} · ${MODE_LABEL[v.mode ?? ''] ?? '모드 불명'} · ${look.label}`
            + ` · ${v.carrierPresent ? '화물 있음' : '화물 없음'} · ${call ? '작업 할당 가능' : '작업 할당 불가'}`;
          // Body at its real place on the segment, about its real size, turned the way it travels.
          const L = 1200, W = 760;
          const label = `${v.code} ${mode}${v.batteryPct == null ? '' : ` ${Math.round(v.batteryPct)}%`}`;
          const labelW = label.length * 125 + 120;
          return (
            <g key={v.id} role="button" tabIndex={0} aria-label={`${title} 상세 보기`}
              style={{ cursor: 'pointer', transform: `translate(${at.x}px, ${at.y}px)` }}
              onClick={() => { if (clicked()) props.onSelect(v); }}
              onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); props.onSelect(v); } }}>
              <title>{title}</title>
              {selected && <circle r={880} fill="none" stroke="#1D6FD8" strokeWidth={36} strokeDasharray="90 60" />}
              <g style={{ transform: `rotate(${heading}deg)` }}>
                <rect x={-L / 2} y={-W / 2} width={L} height={W} rx={90} fill={look.fill}
                  stroke={call ? CALL_ON : CALL_OFF} strokeWidth={70} />
                {/* Front, so the direction of travel reads at a glance. */}
                <path d={`M ${L / 2 - 230} ${-W / 2 + 120} L ${L / 2 - 90} 0 L ${L / 2 - 230} ${W / 2 - 120}`}
                  fill="none" stroke="#172231" strokeOpacity={0.55} strokeWidth={46} strokeLinecap="round" strokeLinejoin="round" />
              </g>
              {/* Carrier on board: green; none: the inactive colour. */}
              <circle r={200} fill={v.carrierPresent ? CARRIER_ON : CARRIER_OFF} stroke="#172231" strokeWidth={30} />
              <g pointerEvents="none">
                <rect x={-labelW / 2} y={-W / 2 - 520} width={labelW} height={300} rx={70} fill="#172231" fillOpacity={0.88} />
                <text y={-W / 2 - 320} textAnchor="middle" fontSize={190} fontWeight={800} style={{ fill: '#fff' }}>{label}</text>
                {status !== 'IDLE' && status !== 'MOVING' && (
                  <text y={W / 2 + 300} textAnchor="middle" fontSize={170} fontWeight={800} style={{ fill: look.fill === VEHICLE_COLOR.PAUSE ? '#5B6B7C' : look.fill }}>
                    {look.label}
                  </text>
                )}
              </g>
            </g>
          );
        })}
      </svg>
    </div>
  );
}

export function MapLegend({ mode = '2D' }: { mode?: '2D' | '3D' }) {
  const swatch = (color: string, label: string) => (
    <span className="li"><svg width="14" height="12"><rect x="1" y="1" width="12" height="10" rx="2" fill="#fff" stroke={color} strokeWidth="2.5" /></svg>{label}</span>
  );
  const fillSwatch = (color: string, label: string) => (
    <span className="li"><svg width="14" height="12"><rect x="1" y="1" width="12" height="10" rx="2" fill={color} stroke="#5B6B7C" strokeWidth="0.8" /></svg>{label}</span>
  );
  return (
    <div className="maplegend">
      <span className="li"><svg width="26" height="8"><line x1="0" y1="4" x2="26" y2="4" stroke={OHT} strokeWidth="3" /></svg>OHT 레일 · 천장</span>
      <span className="li"><svg width="26" height="8"><line x1="0" y1="4" x2="26" y2="4" stroke={AGV} strokeWidth="3" strokeDasharray="7 5" /></svg>AGV 주행로 · 바닥</span>
      <span className="li"><svg width="14" height="12"><rect x="0" y="1" width="14" height="10" rx="2" fill="#fff" stroke={OHT} strokeWidth="2" /></svg>포트</span>
      {fillSwatch(VEHICLE_COLOR.IDLE, '대기')}{fillSwatch(VEHICLE_COLOR.MOVE, '이동')}{fillSwatch(VEHICLE_COLOR.LOAD, '적재·하역')}
      {fillSwatch(VEHICLE_COLOR.CHARGE, '충전')}{fillSwatch(VEHICLE_COLOR.PAUSE, '일시정지')}{fillSwatch(VEHICLE_COLOR.ALARM, '알람')}
      {fillSwatch(VEHICLE_COLOR.MANUAL, '수동')}{fillSwatch(VEHICLE_COLOR.DISCONNECTED, '연결 끊김')}
      {swatch(CALL_ON, '테두리: 작업 할당 가능')}{swatch(CALL_OFF, '테두리: 할당 불가')}
      <span className="li"><svg width="12" height="12"><circle cx="6" cy="6" r="5" fill={CARRIER_ON} stroke="#172231" strokeWidth="1" /></svg>화물 있음</span>
      <span className="li"><svg width="12" height="12"><circle cx="6" cy="6" r="5" fill={CARRIER_OFF} stroke="#172231" strokeWidth="1" /></svg>화물 없음</span>
      <span className="li"><svg width="16" height="10"><rect width="16" height="10" rx="2" fill="#5B6B7C" /></svg>대기 장소</span>
      <span className="li"><svg width="16" height="10"><rect width="16" height="10" rx="2" fill="#2E7D32" /></svg>충전 장소</span>
      <span className="li">R 원격 · S 반자동 · M 수동 · {mode === '3D'
        ? '왼쪽 끌기 회전 · 오른쪽 끌기 이동 · 휠 확대·축소'
        : '휠 확대·축소, 끌어서 이동'}</span>
    </div>
  );
}
