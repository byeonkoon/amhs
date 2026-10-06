/*
 * The large sample layout, laid out as track rather than as drawing.
 *
 * Track is specified the way track is built: a heading, straights, and arcs of a stated radius. So
 * that is how this is written -- a pen that goes forward and turns, dropping a node every so often.
 * Each segment then carries only the small bow that turns its chord back into the arc it belongs
 * to, a few hundred millimetres, instead of a metres-wide bulge invented to look curved.
 *
 * The second rule is what makes node reservation enough: two routes may share no space except the
 * nodes they both hold. A bay is therefore a spur between two junction nodes with nothing on the
 * main line in between -- it leaves at a right angle on a rounded corner, runs its own lane well
 * clear of the main line, and comes back at a right angle. The only places the two routes touch are
 * the diverge and the merge, and each of those is one node, which one vehicle holds at a time.
 *
 * scripts/check-map-geometry.cjs is what proves it: it reports any two pieces of track that pass
 * within a vehicle's clearance without sharing a node.
 */
const fs = require('fs');

const SPACING = 8000;      // how often a node is dropped on a straight, mm
const STALL = 7000;        // how far a charging or waiting stall sits off its lane, mm
const CHORDS = 1;          // one chord per 90 degrees: the first node past a turnout is clear of the line
const nodes = [], segments = [], ports = [], vehicles = [], zones = [];
const byCode = new Map();

function node(code, x, y, extra = {}) {
  const n = { code, xMm: Math.round(x), yMm: Math.round(y), ...extra };
  nodes.push(n); byCode.set(code, n);
  return code;
}
function link(a, b, curveMm = 0) {
  const from = byCode.get(a), to = byCode.get(b);
  const cost = Math.max(1, Math.round(Math.hypot(to.xMm - from.xMm, to.yMm - from.yMm)));
  const bow = Math.round(curveMm);
  segments.push(bow ? { from: a, to: b, costMm: cost, curveMm: bow } : { from: a, to: b, costMm: cost });
}

/** A pen that lays track: it has a position and a heading, and it drops nodes as it goes. */
function pen(prefix, x, y, headingDeg, from = null) {
  let next = 1;
  const state = {
    x, y, h: headingDeg * Math.PI / 180,
    path: [],
    name: () => `${prefix}-${String(next++).padStart(3, '0')}`,
    /** Puts a node where the pen stands and remembers it in order. */
    mark(code = state.name(), extra = {}, bow = 0) {
      const made = node(code, state.x, state.y, extra);
      if (state.path.length) link(state.path[state.path.length - 1], made, bow);
      state.path.push(made);
      return made;
    },
    straight(distance, extra = {}, spacing = SPACING) {
      const steps = Math.max(1, Math.round(distance / spacing));
      for (let i = 0; i < steps; i++) {
        const d = distance / steps;
        state.x += Math.cos(state.h) * d; state.y += Math.sin(state.h) * d;
        state.mark(undefined, extra);
      }
    },
    /** An arc of this radius turning this many degrees; positive turns right on screen. */
    turn(radius, degrees, extra = {}) {
      const total = degrees * Math.PI / 180;
      const chords = Math.max(1, Math.round(Math.abs(degrees) / (90 / CHORDS)));
      const step = total / chords;
      // The bow each chord needs to be its arc, on the outside of the turn.
      const bow = -Math.sign(step) * radius * (1 - Math.cos(Math.abs(step) / 2));
      for (let i = 0; i < chords; i++) {
        const cx = state.x + Math.cos(state.h + Math.sign(step) * Math.PI / 2) * radius;
        const cy = state.y + Math.sin(state.h + Math.sign(step) * Math.PI / 2) * radius;
        const a0 = Math.atan2(state.y - cy, state.x - cx);
        state.x = cx + radius * Math.cos(a0 + step);
        state.y = cy + radius * Math.sin(a0 + step);
        state.h += step;
        state.mark(undefined, extra, bow);
      }
    },
  };
  if (from) state.path.push(from); else state.mark();
  return state;
}

/* ---- the main line: a rounded rectangle driven clockwise ---- */
function mainLine(prefix, x0, y0, width, height, radius) {
  const p = pen(prefix, x0 + radius, y0, 0);
  const runX = width - 2 * radius, runY = height - 2 * radius;
  const runs = [];
  for (const run of [runX, runY, runX, runY]) {
    const from = p.path.length - 1;
    p.straight(run);
    runs.push([from, p.path.length - 1]);   // a bay must begin and end inside one of these
    p.turn(radius, 90);
  }
  // The pen is back where it started: close the ring onto the first node instead of repeating it.
  // The pen has arrived back at the first node's position; that last node is the first one again.
  const last = p.path.pop(), first = p.path[0];
  const k = nodes.findIndex(n => n.code === last);
  if (k >= 0) nodes.splice(k, 1);
  byCode.delete(last);
  const closing = segments.find(s => s.to === last);
  if (closing) { closing.to = first; }
  return { path: p.path, runs };
}

/**
 * A spur between two main-line nodes: out at a right angle on a rounded corner, along its own lane,
 * and back. Everything the main line had between those two nodes is gone -- the main line runs
 * straight from one junction to the other, and the spur is the other way round.
 */
/**
 * A bay: a loop that hangs off the main line and shares one side with it.
 *
 * The vehicle leaves the line at the diverge, turns away from the loop, runs out, along the far
 * side where the work is, back in, and rejoins at the merge. The stretch of main line between the
 * two junctions is the bay's fourth side -- it stays exactly as it was, and a vehicle passing along
 * it is a bay's depth away from anything in the bay.
 *
 * So the two routes share only the diverge and the merge. Each of those is one node, which one
 * vehicle holds at a time, and that is what makes the junction safe without anything else.
 */
function spur(path, atIndex, span, depth, label, extras = {}, laneNodes = 1, radius = 4000) {
  const a = byCode.get(path[atIndex]);
  const dIndex = (atIndex + span) % path.length;
  const d = byCode.get(path[dIndex]);
  const dx = d.xMm - a.xMm, dy = d.yMm - a.yMm, len = Math.hypot(dx, dy);
  const heading = Math.atan2(dy, dx) * 180 / Math.PI;
  const far = len - 4 * radius;              // the far side, once the four corners have taken theirs
  if (far <= SPACING || depth <= 2 * radius) {
    console.warn(`skipped ${label}: junctions ${Math.round(len)}mm apart leaves ${Math.round(far)}mm`);
    return { lane: [], path };
  }
  // Out, along, and back. The ring is driven clockwise, so turning left leaves it.
  const p = pen(label, a.xMm, a.yMm, heading, path[atIndex]);
  p.turn(radius, -90);
  p.straight(depth - 2 * radius, {}, (depth - 2 * radius));
  p.turn(radius, 90);
  const laneCodes = [];
  if (laneNodes <= 1) {
    p.straight(far / 2, extras, far / 2);
    laneCodes.push(p.path[p.path.length - 1]);
    p.straight(far / 2, {}, far / 2);
  } else {
    // Stalls beside the lane, never one behind another. A row of stalls is a queue: the vehicle in
    // front decides when the one behind may leave, and a vehicle that is parked has no reason to
    // move -- so a charger behind a parked vehicle is a charger nobody can reach. Each stall is its
    // own short branch off the lane instead, which is how a charging bay is actually built.
    const spine = [p.path[p.path.length - 1]];
    for (let i = 0; i < laneNodes; i++) {
      p.straight(far / laneNodes, {}, far / laneNodes);
      spine.push(p.path[p.path.length - 1]);
    }
    const entry = byCode.get(path[atIndex]);
    for (let i = 0; i < laneNodes; i++) {
      const s = byCode.get(spine[i]), e = byCode.get(spine[i + 1]);
      const mx = (s.xMm + e.xMm) / 2, my = (s.yMm + e.yMm) / 2;
      const ux = e.xMm - s.xMm, uy = e.yMm - s.yMm, len2 = Math.hypot(ux, uy) || 1;
      // Off the side away from the main line, so a stall never reaches back towards it.
      let ox = -uy / len2 * STALL, oy = ux / len2 * STALL;
      if (Math.hypot(mx + ox - entry.xMm, my + oy - entry.yMm)
        < Math.hypot(mx - ox - entry.xMm, my - oy - entry.yMm)) { ox = -ox; oy = -oy; }
      // A bay may name and mark each stall for itself, so the chargers and the places to stand
      // afterwards can be one area rather than two at opposite ends of the loop.
      const spec = typeof extras === 'function' ? extras(i) : extras;
      const { code, ...marks } = spec;
      const stall = node(code ?? `${label}-${i + 1}`, mx + ox, my + oy, marks);
      link(spine[i], stall);
      link(stall, spine[i + 1]);
      laneCodes.push(stall);
    }
  }
  p.turn(radius, 90);
  p.straight(depth - 2 * radius, {}, (depth - 2 * radius));
  p.turn(radius, -90);
  // The pen now stands on the merge; that node is already there, so its last one is dropped.
  const last = p.path[p.path.length - 1];
  nodes.splice(nodes.findIndex(n => n.code === last), 1);
  byCode.delete(last);
  segments.splice(segments.findIndex(s => s.to === last), 1);
  link(p.path[p.path.length - 2], path[dIndex], 0);
  // A single stall is the lane itself and still carries the pen's own name; the stalls of a bay
  // with more than one were named as they were made.
  if (laneNodes <= 1) laneCodes.forEach((code, i) => {
    const n = byCode.get(code);
    byCode.delete(code); n.code = label; byCode.set(label, n);
    for (const seg of segments) { if (seg.from === code) seg.from = label; if (seg.to === code) seg.to = label; }
    laneCodes[i] = label;
  });
  return { lane: laneCodes, path };
}

/** Spreads the bays a side asks for along that side's straight run, leaving room between them. */
function planBays(runs, sides, prefix) {
  const plan = [];
  let numbered = 0;
  sides.forEach((side, i) => {
    const [from, to] = runs[i];
    const room = to - from;
    const each = Math.floor(room / side.bays);
    for (let k = 0; k < side.bays; k++) {
      const at = from + k * each + Math.max(1, Math.floor((each - side.span) / 2));
      if (at + side.span > to) continue;      // it would reach into the corner
      plan.push({
        at, span: side.span, depth: side.depth,
        label: side.label ?? `${prefix}${String(++numbered).padStart(2, '0')}`,
        extras: side.extras, laneNodes: side.laneNodes,
      });
    }
  });
  return plan;
}

/* ================= OHT: main line, work bays, an inspection spur and a waiting spur ============ */
const ohtRing = mainLine('OHT-M', 20000, 20000, 190000, 104000, 7000);
let ohtPath = ohtRing.path;
// Three bays along the top, three along the bottom, and one on each side -- the inspection loop
// and the waiting loop. Every one of them sits inside a straight run, never across a corner.
const ohtSides = [
  { bays: 3, span: 4, depth: 30000 },
  { bays: 1, span: 5, depth: 34000, label: 'OHT-INSP', laneNodes: 3 },
  { bays: 3, span: 4, depth: 30000 },
  { bays: 1, span: 4, depth: 26000, label: 'OHT-WAIT', extras: { waitFor: 'OHT' }, laneNodes: 2 },
];
const ohtPlan = planBays(ohtRing.runs, ohtSides, 'OHT-B');
const ohtLanes = new Map();
for (const plan of ohtPlan.slice().sort((x, y) => y.at - x.at)) {
  const made = spur(ohtPath, plan.at, plan.span, plan.depth, plan.label,
    plan.extras ?? {}, plan.laneNodes ?? 1);
  ohtPath = made.path;
  ohtLanes.set(plan.label, made.lane);
}
ohtPlan.filter(p => p.label.startsWith('OHT-B')).forEach((p, i) => {
  const lane = ohtLanes.get(p.label) ?? [];
  if (lane.length) ports.push({
    code: `BAY-O${String(i + 1).padStart(2, '0')}`, node: lane[0],
    portType: i % 2 === 0 ? 'LOAD' : 'UNLOAD', accessVehicleType: 'OHT',
  });
});
const inspection = ohtLanes.get('OHT-INSP') ?? [];
if (inspection.length)
  zones.push({ code: 'INSPECTION', capacity: inspection.length, kind: 'MAINTENANCE', nodes: inspection });

/* ================= AGV: its own loop, bays, chargers and a waiting spur ======================== */
const agvRing = mainLine('AGV-M', 20000, 180000, 170000, 84000, 4500);
let agvPath = agvRing.path;
// Ten AGVs: four chargers and eight places to stand, in one bay. Fewer chargers than vehicles is
// ordinary and the controller queues for them, but a vehicle that has finished charging must have
// somewhere to go or the charger it stands on never comes free -- hence a stall for every vehicle
// and two over. They are in the same bay because stepping aside should cost a few metres: parking
// on the far side of the loop would spend more charge getting there than the charge was worth.
const stalls = i => i < 4
  ? { code: `AGV-CHG-${i + 1}`, chargePoint: true }
  : { code: `AGV-WAIT-${i - 3}`, waitFor: 'AGV' };
const agvSides = [
  { bays: 2, span: 4, depth: 22000 },
  { bays: 1, span: 4, depth: 22000 },
  { bays: 1, span: 8, depth: 26000, label: 'AGV-PARK', extras: stalls, laneNodes: 12 },
  { bays: 1, span: 4, depth: 22000 },
];
const agvPlan = planBays(agvRing.runs, agvSides, 'AGV-B');
const agvLanes = new Map();
for (const plan of agvPlan.slice().sort((x, y) => y.at - x.at)) {
  const made = spur(agvPath, plan.at, plan.span, plan.depth, plan.label,
    plan.extras ?? {}, plan.laneNodes ?? 1, 3000);
  agvPath = made.path;
  agvLanes.set(plan.label, made.lane);
}
agvPlan.filter(p => p.label.startsWith('AGV-B')).forEach((p, i) => {
  const lane = agvLanes.get(p.label) ?? [];
  if (lane.length) ports.push({
    code: `BAY-A${String(i + 1).padStart(2, '0')}`, node: lane[0],
    portType: i % 2 === 0 ? 'LOAD' : 'UNLOAD', accessVehicleType: 'AGV',
  });
});

/* ================= the fleet ================= */
const chargeNodes = nodes.filter(n => n.chargePoint).map(n => n.code);
const onMain = path => path.filter(c => byCode.has(c));
const ohtMain = onMain(ohtPath), agvMain = onMain(agvPath);
for (let i = 0; i < 15; i++)
  vehicles.push({ code: `OHT-${String(11 + i).padStart(2, '0')}`, name: `OHT ${11 + i}`, vType: 'OHT',
    initialNode: ohtMain[Math.floor(i * ohtMain.length / 15)], speedMmS: 2000, chargingPolicy: 'DISABLED' });
for (let i = 0; i < 10; i++)
  vehicles.push({ code: `AGV-${String(11 + i).padStart(2, '0')}`, name: `AGV ${11 + i}`, vType: 'AGV',
    initialNode: agvMain[Math.floor(i * agvMain.length / 10)], speedMmS: 1000,
    chargingPolicy: 'NEAREST', charge: chargeNodes[i % chargeNodes.length] });

for (const n of nodes) { delete n.lane; }
const doc = { nodes, segments, ports, vehicles, zones };
const line = o => JSON.stringify(o);
const block = (name, rows) => `  "${name}": [\n${rows.map(r => '    ' + line(r)).join(',\n')}\n  ]`;
fs.writeFileSync(process.argv[2],
  '{\n' + ['nodes', 'segments', 'ports', 'vehicles', 'zones'].map(k => block(k, doc[k])).join(',\n') + '\n}\n');

const bows = segments.filter(s => s.curveMm).map(s => Math.abs(s.curveMm));
console.log(`nodes ${nodes.length} (OHT ${nodes.filter(n => n.code.startsWith('OHT')).length},`
  + ` AGV ${nodes.filter(n => n.code.startsWith('AGV')).length}) · segments ${segments.length}`
  + ` · curved ${bows.length} · bow ${Math.min(...bows)}~${Math.max(...bows)}mm`
  + ` · ports ${ports.length} · vehicles ${vehicles.length} · zones ${zones.length}`);
