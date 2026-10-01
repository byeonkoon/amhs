import React from 'react';
import { MODE_LABEL, STATUS_LABEL, type MapData, type VehicleSnapshot } from '../../api';

const OHT = '#1D6FD8';
const AGV = '#9A4A9E';
const PAD = 900;
const MIN_SCALE = 1, MAX_SCALE = 12;

/** Status colours: what an operator has to notice first is a fault, then a stop, then work. */
const STATUS_COLOR: Record<string, string> = {
  ERROR: '#C62828', PAUSED: '#E0A100', RESET: '#E0A100', CHARGING: '#2E7D32',
  LOADING: '#EF6C00', UNLOADING: '#EF6C00', MOVING: '#0B7A75', IDLE: '#5B6B7C',
};

type View = { x: number; y: number; w: number; h: number };

/**
 * Layout map. OHT and AGV are drawn as separate layers: solid line for the overhead rail,
 * dashed for the floor route, and different vehicle shapes. They are never joined by an edge,
 * because the graphs themselves are not connected.
 *
 * Vehicles are drawn where they last reported, not where they were configured, with their status
 * as the outline colour, a dot when they carry, and their mode as a letter (R remote, S semi-auto,
 * M manual). The wheel zooms around the pointer, dragging pans, and a node can be picked as a
 * target by clicking it.
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
  const typeOfNode = (id: number): 'OHT' | 'AGV' => {
    const port = map.ports.find(p => p.linkedNodeId === id);
    if (port) return port.accessVehicleType;
    return node(id)?.code.startsWith('AGV') ? 'AGV' : 'OHT';
  };
  const visible = (t: 'OHT' | 'AGV') => layer === 'all' || layer === t;

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
          return <line key={e.id} x1={a.x} y1={a.y} x2={b.x} y2={b.y}
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
          return (
            <g key={p.id} pointerEvents="none">
              <rect x={n.x - 420} y={n.y + 380} width={840} height={300} rx={50}
                fill="#fff" stroke={p.accessVehicleType === 'OHT' ? OHT : AGV} strokeWidth={16} />
              <text x={n.x} y={n.y + 580} textAnchor="middle" fontSize={165} fontWeight={600}>{p.code}</text>
            </g>
          );
        })}

        {vehicles.map(v => {
          const n = v.nodeId === null ? undefined : node(v.nodeId);
          if (!n || !visible(v.vType)) return null;
          const selected = props.selectedId === v.id;
          const fill = !v.online ? '#9AA5B1' : v.vType === 'OHT' ? OHT : AGV;
          const status = v.status ?? (v.idle ? 'IDLE' : 'MOVING');
          const ring = STATUS_COLOR[status] ?? '#5B6B7C';
          const mode = v.mode === 'REMOTE' ? 'R' : v.mode === 'SEMI_AUTO' ? 'S' : v.mode === 'MANUAL' ? 'M' : '?';
          const title = `${v.code} · ${MODE_LABEL[v.mode ?? ''] ?? '모드 불명'} · ${STATUS_LABEL[status] ?? status}`
            + ` · ${v.carrierPresent ? '화물 있음' : '화물 없음'}${v.online ? '' : ' · 오프라인'}`;
          return (
            <g key={v.id} role="button" tabIndex={0} aria-label={`${title} 상세 보기`}
              style={{ cursor: 'pointer' }}
              onClick={() => { if (clicked()) props.onSelect(v); }}
              onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); props.onSelect(v); } }}>
              <title>{title}</title>
              {/* OHT hangs from the rail as a rectangle; AGV runs on the floor as a hexagon. */}
              {v.vType === 'OHT'
                ? <rect x={n.x - 340} y={n.y - 620} width={680} height={330} rx={50} fill={fill}
                    stroke={selected ? '#172231' : ring} strokeWidth={selected ? 40 : 34} />
                : <polygon points={`${n.x - 300},${n.y - 455} ${n.x - 190},${n.y - 620} ${n.x + 190},${n.y - 620} ${n.x + 300},${n.y - 455} ${n.x + 190},${n.y - 290} ${n.x - 190},${n.y - 290}`}
                    fill={fill} stroke={selected ? '#172231' : ring} strokeWidth={selected ? 40 : 34} />}
              <text x={n.x - 40} y={n.y - 400} textAnchor="middle" fontSize={128} fontWeight={600} fill="#fff">{v.code}</text>
              {/* Mode letter, top right; a filled dot when the vehicle carries something. */}
              <text x={n.x + 255} y={n.y - 505} textAnchor="middle" fontSize={96} fontWeight={700} fill="#fff">{mode}</text>
              {v.carrierPresent && <circle cx={n.x + 255} cy={n.y - 375} r={52} fill="#FFD54F" stroke="#172231" strokeWidth={12} />}
              {status !== 'IDLE' && status !== 'MOVING' && (
                <text x={n.x} y={n.y - 690} textAnchor="middle" fontSize={120} fontWeight={700} fill={ring}>
                  {STATUS_LABEL[status] ?? status}
                </text>
              )}
            </g>
          );
        })}
      </svg>
    </div>
  );
}

export function MapLegend() {
  const swatch = (color: string, label: string) => (
    <span className="li"><svg width="14" height="12"><rect x="1" y="1" width="12" height="10" rx="2" fill="#fff" stroke={color} strokeWidth="2.5" /></svg>{label}</span>
  );
  return (
    <div className="maplegend">
      <span className="li"><svg width="26" height="8"><line x1="0" y1="4" x2="26" y2="4" stroke={OHT} strokeWidth="3" /></svg>OHT 레일 · 천장</span>
      <span className="li"><svg width="26" height="8"><line x1="0" y1="4" x2="26" y2="4" stroke={AGV} strokeWidth="3" strokeDasharray="7 5" /></svg>AGV 주행로 · 바닥</span>
      <span className="li"><svg width="14" height="12"><rect x="0" y="1" width="14" height="10" rx="2" fill="#fff" stroke={OHT} strokeWidth="2" /></svg>포트</span>
      {swatch(STATUS_COLOR.MOVING, '이동')}{swatch(STATUS_COLOR.IDLE, '대기')}{swatch(STATUS_COLOR.LOADING, '적재·하역')}
      {swatch(STATUS_COLOR.CHARGING, '충전')}{swatch(STATUS_COLOR.PAUSED, '일시정지·리셋')}{swatch(STATUS_COLOR.ERROR, '에러')}
      <span className="li"><svg width="12" height="12"><circle cx="6" cy="6" r="5" fill="#FFD54F" stroke="#172231" strokeWidth="1" /></svg>화물 있음</span>
      <span className="li">R 원격 · S 반자동 · M 수동 · 휠 확대·축소, 끌어서 이동</span>
    </div>
  );
}
