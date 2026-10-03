import React from 'react';
import {
  SITE_ID, type AssignmentView, type Deadlock, type DeadlockPlan, type DeadlockRecovery, type DeviceCommand, type NodeHold, type TransportOrder,
  type VehicleHeading, type VehicleMove, type VehicleSnapshot, type ZoneOccupancy,
} from './api';

/** What the server pushes, under the names it sends them. Each part is replaced whole. */
export type LiveParts = Partial<{
  revisionId: number; vehicles: VehicleSnapshot[]; holds: NodeHold[]; headings: VehicleHeading[];
  moves: VehicleMove[]; assignments: AssignmentView[]; orders: TransportOrder[]; deadlocks: Deadlock[];
  zones: ZoneOccupancy[]; inDoubt: DeviceCommand[]; deadlockPlans: DeadlockPlan[]; recoveries: DeadlockRecovery[];
}>;
/**
 * Every message names the parts the server could not read and has not read since. Freshness travels
 * on the message rather than being inferred from whether data changed, because those are different
 * questions: a site can be quiet with everything readable, and busy with half of it unreadable.
 * Reading what moves says nothing about work the server still cannot see.
 */
export type LiveMessage = {
  type: 'snapshot' | 'delta' | 'heartbeat'; epoch: string; seq: number; at: string;
  unread?: string[]; data?: LiveParts;
};
/** Where a screen stands: the server run (epoch), the number applied, step, and what is not current. */
export type LiveState = {
  epoch: string | null; seq: number; parts: LiveParts; synced: boolean; unread: string[];
};
export const LIVE_START: LiveState = { epoch: null, seq: 0, parts: {}, synced: false, unread: [] };

/**
 * One message applied to where the screen stands. A snapshot replaces everything. A delta applies
 * only if it is the very next number of the same run; an older or repeated one is dropped (it never
 * takes the screen back); a gap or another run means the screen is out of step and must ask again.
 * A heartbeat carrying a number the screen has not reached means a delta went missing.
 *
 * <p>What is unread is taken from every message the screen stays in step with, including a
 * heartbeat and a repeated delta. Those carry no new data and still carry the current answer to a
 * different question: whether what is already on screen can be trusted as the present.
 */
export function applyLive(state: LiveState, msg: LiveMessage): { state: LiveState; resync: boolean; changed: boolean } {
  const unread = msg.unread ?? [];
  if (msg.type === 'snapshot') {
    return {
      state: { epoch: msg.epoch, seq: msg.seq, parts: { ...(msg.data ?? {}) }, synced: true, unread },
      resync: false, changed: true,
    };
  }
  // Already asked again: wait for the snapshot, whatever comes before it.
  if (!state.synced) return { state, resync: false, changed: false };
  const outOfStep = { state: { ...state, synced: false }, resync: true, changed: false };
  if (msg.epoch !== state.epoch) return outOfStep;
  const held = { state: { ...state, unread }, resync: false, changed: false };
  // A heartbeat carries no data; it still says where the channel stands and what it cannot read.
  if (msg.type === 'heartbeat') return msg.seq > state.seq ? outOfStep : held;
  if (msg.seq <= state.seq) return held;
  if (msg.seq !== state.seq + 1) return outOfStep;
  return {
    state: { ...state, seq: msg.seq, parts: { ...state.parts, ...(msg.data ?? {}) }, unread },
    resync: false, changed: true,
  };
}

/**
 * live: in step with the server. resyncing: asked again, waiting. down: no connection; trying
 * again. stale: connected and in step, but the server named parts of the site it cannot read, so
 * what is on screen for those is the last thing it could read and not the present.
 */
export type LiveStatus = 'connecting' | 'live' | 'resyncing' | 'down' | 'stale';
/** `unread` names the parts behind a `stale`, so a screen can say which of them is not current. */
export type Live = { status: LiveStatus; parts: LiveParts; seq: number; at: Date | null; unread: string[] };

const SILENCE_MS = 12_000;     // the server sends at least a heartbeat every 5 s
const RESYNC_MS = 4_000;       // a snapshot asked for that does not come: start over
const BACKOFF_MS = [500, 1000, 2000, 4000, 8000];

/**
 * The site's live feed while enabled. Reconnects by itself with growing pauses, and drops a
 * connection that has gone silent or did not answer a request to resynchronise.
 */
export function useLive(enabled: boolean): Live {
  const [live, setLive] = React.useState<Live>({ status: 'connecting', parts: {}, seq: 0, at: null, unread: [] });
  React.useEffect(() => {
    if (!enabled) { setLive({ status: 'down', parts: {}, seq: 0, at: null, unread: [] }); return; }
    let state = LIVE_START;
    let socket: WebSocket | null = null;
    let attempt = 0;
    let stopped = false;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let lastHeard = Date.now();
    let askedAt = 0;
    let shown: LiveStatus | null = null;
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${scheme}://${location.host}/api/v1/sites/${SITE_ID}/live`;

    const publish = (status: LiveStatus) => {
      shown = status;
      setLive({ status, parts: state.parts, seq: state.seq, at: new Date(), unread: state.unread });
    };
    const open = () => {
      if (stopped) return;
      state = LIVE_START;
      shown = 'connecting';
      setLive(l => ({ ...l, status: 'connecting' }));
      const ws = new WebSocket(url);
      socket = ws;
      ws.onopen = () => { attempt = 0; lastHeard = Date.now(); };
      ws.onmessage = event => {
        lastHeard = Date.now();
        let msg: LiveMessage;
        try { msg = JSON.parse(event.data as string) as LiveMessage; } catch { return; }
        const step = applyLive(state, msg);
        state = step.state;
        if (step.resync) {
          askedAt = Date.now();
          try { ws.send(JSON.stringify({ type: 'resync' })); } catch { ws.close(); }
          publish('resyncing');
          return;
        }
        if (step.changed) askedAt = 0;
        // Whether the screen is current follows what the server says it cannot read, not whether
        // this message happened to carry data. A delta about vehicles does not make unreadable
        // work current, and a quiet site that becomes readable again sends nothing but a heartbeat.
        const next: LiveStatus = state.unread.length > 0 ? 'stale' : 'live';
        if (step.changed || next !== shown) publish(next);
      };
      ws.onclose = () => {
        if (socket === ws) socket = null;
        if (stopped) return;
        shown = 'down';
        setLive(l => ({ ...l, status: 'down' }));
        retryTimer = setTimeout(open, BACKOFF_MS[Math.min(attempt++, BACKOFF_MS.length - 1)]);
      };
    };
    const watchdog = setInterval(() => {
      if (!socket || socket.readyState !== WebSocket.OPEN) return;
      const now = Date.now();
      if (now - lastHeard > SILENCE_MS || (askedAt && now - askedAt > RESYNC_MS)) { askedAt = 0; socket.close(); }
    }, 1000);
    open();
    return () => {
      stopped = true;
      clearInterval(watchdog);
      clearTimeout(retryTimer);
      socket?.close();
    };
  }, [enabled]);
  return live;
}
