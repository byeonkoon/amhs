import type { MapData, VehicleSnapshot } from '../../api';

/**
 * Segment shape, the same as the server's: a segment bent by curveMm is a quadratic curve through
 * the point curveMm away from the middle of the straight line, on the side of (dy, -dx) — the left
 * as drawn on the plan when going from the first node to the second. Zero is straight.
 */
export function controlPoint(ax: number, ay: number, bx: number, by: number, curveMm: number) {
  const dx = bx - ax, dy = by - ay, len = Math.hypot(dx, dy);
  const mx = (ax + bx) / 2, my = (ay + by) / 2;
  if (!len || !curveMm) return { x: mx, y: my };
  return { x: mx + (dy / len) * 2 * curveMm, y: my - (dx / len) * 2 * curveMm };
}

/** SVG path of a segment, straight or curved. */
export function segmentPath(ax: number, ay: number, bx: number, by: number, curveMm = 0): string {
  if (!curveMm) return `M ${ax} ${ay} L ${bx} ${by}`;
  const c = controlPoint(ax, ay, bx, by, curveMm);
  return `M ${ax} ${ay} Q ${c.x} ${c.y} ${bx} ${by}`;
}

/** Points along a segment, for drawing it in 3D. */
export function segmentPoints(ax: number, ay: number, bx: number, by: number, curveMm = 0, samples = 24) {
  if (!curveMm) return [{ x: ax, y: ay }, { x: bx, y: by }];
  const c = controlPoint(ax, ay, bx, by, curveMm);
  const out: { x: number; y: number }[] = [];
  for (let i = 0; i <= samples; i++) {
    const t = i / samples, u = 1 - t;
    out.push({ x: u * u * ax + 2 * u * t * c.x + t * t * bx, y: u * u * ay + 2 * u * t * c.y + t * t * by });
  }
  return out;
}

/** Where to draw a vehicle: between nodes where it says it is, otherwise on its node. */
export function vehiclePoint(map: MapData, v: VehicleSnapshot): { x: number; y: number } | null {
  if (v.xMm != null && v.yMm != null) return { x: v.xMm, y: v.yMm };
  const n = v.nodeId === null ? undefined : map.nodes.find(x => x.id === v.nodeId);
  return n ? { x: n.x, y: n.y } : null;
}

/** What a port is called on the map: its equipment when one is linked, the port code otherwise. */
export function portTitle(p: MapData['ports'][number]): { main: string; sub: string | null } {
  if (p.equipmentName || p.equipmentCode)
    return { main: p.equipmentName || p.equipmentCode!, sub: `${p.equipmentCode ?? ''} · ${p.code}`.replace(/^ · /, '') };
  return { main: p.code, sub: null };
}

/** Nodes some vehicle waits at (home) or charges at, with the vehicles that use them. */
export function vehiclePlaces(vehicles: VehicleSnapshot[]) {
  const home = new Map<number, string[]>(), charge = new Map<number, string[]>();
  for (const v of vehicles) {
    if (v.homeNodeId != null) home.set(v.homeNodeId, [...(home.get(v.homeNodeId) ?? []), v.code]);
    if (v.chargeNodeId != null) charge.set(v.chargeNodeId, [...(charge.get(v.chargeNodeId) ?? []), v.code]);
  }
  return { home, charge };
}
