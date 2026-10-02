import React from 'react';
import type { MapData, VehicleSnapshot } from '../../api';
import { vehiclePoint } from './geometry';

type Sample = { t: number; x: number; y: number };

/**
 * Positions as they were reported, replayed a little behind real time so that every frame lies
 * between two known points. Each report carries the time the vehicle said it was there, so the
 * spacing of network answers does not show; what shows is the vehicle's own pace.
 *
 * The clock of the reports is the server's. The smallest gap seen between receiving a report and
 * its stamp stands for clock difference plus the quickest delivery, and replay runs that much plus
 * `delayMs` behind the browser's clock.
 */
export class MotionBuffer {
  private samples = new Map<number, Sample[]>();
  private offset = Number.POSITIVE_INFINITY;

  constructor(private delayMs = 600) {}

  push(map: MapData, vehicles: VehicleSnapshot[], receivedAt = Date.now()) {
    const seen = new Set<number>();
    for (const v of vehicles) {
      const p = vehiclePoint(map, v);
      if (!p) continue;
      seen.add(v.id);
      const t = v.reportedAt ? Date.parse(v.reportedAt) : receivedAt;
      if (Number.isFinite(t)) this.offset = Math.min(this.offset, receivedAt - t);
      const list = this.samples.get(v.id) ?? [];
      const last = list[list.length - 1];
      if (last && t <= last.t) continue;
      // After standing a while, it left at most a moment before this report, not when it last
      // reported standing: hold the old place until shortly before the new one.
      if (last && t - last.t > 700 && (last.x !== p.x || last.y !== p.y))
        list.push({ t: t - 300, x: last.x, y: last.y });
      list.push({ t, x: p.x, y: p.y });
      while (list.length > 2 && list[1].t < t - 5000) list.shift();
      this.samples.set(v.id, list);
    }
    for (const id of [...this.samples.keys()]) if (!seen.has(id)) this.samples.delete(id);
  }

  /** Where to draw the vehicle at browser time `now`. */
  at(id: number, now = Date.now()): { x: number; y: number } | null {
    const list = this.samples.get(id);
    if (!list || list.length === 0) return null;
    const rt = now - (Number.isFinite(this.offset) ? this.offset : 0) - this.delayMs;
    if (rt <= list[0].t) return { x: list[0].x, y: list[0].y };
    for (let i = list.length - 1; i >= 0; i--) {
      const a = list[i];
      if (a.t <= rt) {
        const b = list[i + 1];
        if (!b) return { x: a.x, y: a.y };
        const f = (rt - a.t) / (b.t - a.t);
        return { x: a.x + (b.x - a.x) * f, y: a.y + (b.y - a.y) * f };
      }
    }
    return { x: list[0].x, y: list[0].y };
  }
}

/**
 * Smoothed positions for a React view, recomputed every animation frame while anything is still
 * catching up. Returns positions by vehicle id.
 */
export function useSmoothPositions(map: MapData, vehicles: VehicleSnapshot[]) {
  const buffer = React.useRef(new MotionBuffer());
  const [frame, setFrame] = React.useState<Map<number, { x: number; y: number }>>(new Map());
  React.useEffect(() => { buffer.current.push(map, vehicles); }, [map, vehicles]);
  React.useEffect(() => {
    let raf = 0;
    let previous = '';
    const tick = () => {
      const next = new Map<number, { x: number; y: number }>();
      for (const v of vehicles) {
        const p = buffer.current.at(v.id);
        if (p) next.set(v.id, p);
      }
      // Re-render only when something moved by more than a millimetre.
      const key = [...next].map(([id, p]) => `${id}:${Math.round(p.x)}:${Math.round(p.y)}`).join('|');
      if (key !== previous) { previous = key; setFrame(next); }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [vehicles]);
  return frame;
}
