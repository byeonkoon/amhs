import React from 'react';
import type { MapData, Vehicle } from '../../api';

const OHT = '#1D6FD8';
const AGV = '#9A4A9E';
const PAD = 900;

/**
 * Layout map. OHT and AGV are drawn as separate layers: solid line for the overhead rail,
 * dashed for the floor route, and different vehicle shapes. They are never joined by an edge,
 * because the graphs themselves are not connected.
 */
export function MapView(props: {
  map: MapData;
  vehicles: Vehicle[];
  selectedId: number | null;
  onSelect: (vehicle: Vehicle) => void;
  layer: 'all' | 'OHT' | 'AGV';
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
  const minX = Math.min(...xs) - PAD, maxX = Math.max(...xs) + PAD;
  const minY = Math.min(...ys) - PAD, maxY = Math.max(...ys) + PAD;

  return (
    <div className="mapwrap">
      <svg viewBox={`${minX} ${minY} ${maxX - minX} ${maxY - minY}`} role="img"
        aria-label="OHT 천장 레일과 AGV 바닥 주행로가 분리된 설비 지도">
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
          return (
            <g key={n.id}>
              <circle cx={n.x} cy={n.y} r={62} fill="#fff" stroke={t === 'OHT' ? OHT : AGV} strokeWidth={18} />
              <text x={n.x} y={n.y + 250} textAnchor="middle" fontSize={165}>{n.code}</text>
            </g>
          );
        })}

        {map.ports.map(p => {
          const n = node(p.linkedNodeId);
          if (!n || !visible(p.accessVehicleType)) return null;
          return (
            <g key={p.id}>
              <rect x={n.x - 420} y={n.y + 380} width={840} height={300} rx={50}
                fill="#fff" stroke={p.accessVehicleType === 'OHT' ? OHT : AGV} strokeWidth={16} />
              <text x={n.x} y={n.y + 580} textAnchor="middle" fontSize={165} fontWeight={600}>{p.code}</text>
            </g>
          );
        })}

        {vehicles.map(v => {
          const n = node(v.currentNodeId);
          if (!n || !visible(v.vType)) return null;
          const selected = props.selectedId === v.id;
          const fill = v.vType === 'OHT' ? OHT : AGV;
          return (
            <g key={v.id} role="button" tabIndex={0} aria-label={`${v.code} 상세 보기`}
              style={{ cursor: 'pointer' }}
              onClick={() => props.onSelect(v)}
              onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); props.onSelect(v); } }}>
              {/* OHT hangs from the rail as a rectangle; AGV runs on the floor as a hexagon. */}
              {v.vType === 'OHT'
                ? <rect x={n.x - 340} y={n.y - 620} width={680} height={330} rx={50} fill={fill}
                    stroke={selected ? '#172231' : 'none'} strokeWidth={26} />
                : <polygon points={`${n.x - 300},${n.y - 455} ${n.x - 190},${n.y - 620} ${n.x + 190},${n.y - 620} ${n.x + 300},${n.y - 455} ${n.x + 190},${n.y - 290} ${n.x - 190},${n.y - 290}`}
                    fill={fill} stroke={selected ? '#172231' : 'none'} strokeWidth={26} />}
              <text x={n.x} y={n.y - 400} textAnchor="middle" fontSize={128} fontWeight={600} fill="#fff">{v.code}</text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}

export function MapLegend() {
  return (
    <div className="maplegend">
      <span className="li"><svg width="26" height="8"><line x1="0" y1="4" x2="26" y2="4" stroke={OHT} strokeWidth="3" /></svg>OHT 레일 · 천장</span>
      <span className="li"><svg width="26" height="8"><line x1="0" y1="4" x2="26" y2="4" stroke={AGV} strokeWidth="3" strokeDasharray="7 5" /></svg>AGV 주행로 · 바닥</span>
      <span className="li"><svg width="14" height="12"><rect x="0" y="1" width="14" height="10" rx="2" fill="#fff" stroke={OHT} strokeWidth="2" /></svg>포트 · 접근 차종별</span>
      <span className="li">좌표 mm · 유향 edge · 두 계층은 연결되지 않음</span>
    </div>
  );
}
