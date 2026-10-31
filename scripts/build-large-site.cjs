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

/**
 * How many times over. `node build-large-site.cjs out.json 3` is the same floor with three times
 * the bays, three times the fleet, and loops long enough to hold them.
 *
 * It scales the one dimension a fab scales in: a line gets longer and carries more bays. The loops
 * do not get wider or nest, because the shape is what makes node reservation sufficient here -- two
 * routes meeting only at nodes they both hold -- and a shape that has been proved is not worth
 * trading for a bigger number.
 *
 * What does not multiply: the inspection spur, the OHT waiting spur. There is one of each on a
 * floor however long the floor is. Charging and parking do multiply, as one bay per ten AGVs,
 * because a stall is per vehicle -- subdividing one bay into thirty stalls would put them a
 * vehicle's length apart and call it a floor.
 *
 * At 1 this writes exactly the file it wrote before it took an argument.
 */
const SCALE = Math.max(1, Math.round(Number(process.argv[3] ?? 1)));
const OHT_RUN = 144000 * SCALE;   // the straight of an OHT loop, mm: what the bays are spread along
const AGV_RUN = 140000 * SCALE;
/** n copies of a pattern, in order: the plain bays that repeat along a longer line. */
const repeat = (n, pattern) => Array.from({ length: n }, () => pattern).flat();
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

/**
 * The interbay: a ring driven clockwise, near enough a circle, with a bay off each side.
 *
 * <p>A circle would be the honest shape for a loop that is only ever driven round, but a bay has
 * to leave it at a right angle and come back, and there is no right angle to an arc -- a spur off
 * one would leave at a slant and foul the line it just left. So the ring is a regular polygon with
 * its corners eased: every side is a straight a bay can sit squarely in, and with enough sides the
 * thing reads as a circle to anybody looking at it.
 *
 * <p>Eight sides is what the numbers allow. The straight left after the corners are eased has to
 * hold a bay's span with room either end, and fewer, longer sides is what buys that -- sixteen
 * would look rounder and leave no side long enough to put anything on.
 */
function ring(prefix, cx, cy, radius, sides, corner) {
  const half = Math.PI / sides;                 // half the angle one side subtends at the centre
  const edge = 2 * radius * Math.sin(half);     // corner to corner
  const run = edge - 2 * corner * Math.tan(half);   // what is left once the corners are eased
  const apothem = radius * Math.cos(half);      // centre to the middle of a side
  // Start in the middle of the bottom side, driving right, so the ring comes out centred on
  // (cx, cy) and turns the same way round as everything else here.
  const p = pen(prefix, cx - run / 2, cy - apothem, 0);
  const runs = [];
  for (let i = 0; i < sides; i++) {
    const from = p.path.length - 1;
    p.straight(run);
    runs.push([from, p.path.length - 1]);   // a bay must begin and end inside one of these
    p.turn(corner, 360 / sides);
  }
  // The pen is back where it started: close the ring onto the first node instead of repeating it.
  const last = p.path.pop(), first = p.path[0];
  const k = nodes.findIndex(n => n.code === last);
  if (k >= 0) nodes.splice(k, 1);
  byCode.delete(last);
  const closing = segments.find(s => s.to === last);
  if (closing) { closing.to = first; }
  return { path: p.path, runs };
}

/**
 * A loop with semicircular ends, driven the same way round as the main line.
 *
 * <p>An overhead rail is hung from a structure and follows it, so its interbay is a rectangle with
 * its corners eased. A floor route is not hung from anything: it is an aisle taken round, and its
 * ends are turns a vehicle can hold a steady wheel through. Giving the two networks the same
 * outline would be drawing the rail twice and calling one of them AGV.
 *
 * <p>Only the two straights carry bays -- a bay off an arc would leave the line at a slant and
 * foul it -- so this returns those two as the runs a bay may sit in.
 */
function oval(prefix, x0, y0, width, height) {
  const r = height / 2;
  const run = width - 2 * r;
  const p = pen(prefix, x0 + r, y0, 0);
  const runs = [];
  for (let i = 0; i < 2; i++) {
    const from = p.path.length - 1;
    p.straight(run);
    runs.push([from, p.path.length - 1]);
    // Fifteen degrees at a time: a chord of a half-circle this size would put the nodes twice as
    // far apart as the straights do, and a vehicle is only ever as precisely placed as its nodes.
    for (let k = 0; k < 12; k++) p.turn(r, 15);
  }
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
  const ownFirst = segments.length;          // everything this bay adds, for the bow pass at the end
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
    // Stalls on the lane itself, one after another along the far side of the bay.
    //
    // They used to hang off it, each on its own short branch, so that no vehicle could ever be
    // behind another. That is how a charging bay is often built and it drew badly: a row of spikes
    // off the loop, which is not what any of the rest of this layout looks like.
    //
    // On the lane they are a queue, and the queue is the honest part: the bay is a loop driven one
    // way, so a vehicle leaves by going on round rather than by backing out, and a vehicle still
    // charging does hold up the one behind it. That is what a charging stand does on a floor, and
    // it is the controller's to schedule -- there are more stalls than vehicles so there is always
    // somewhere to go, and a vehicle that has finished moves on to a waiting stall and frees its
    // charger.
    for (let i = 0; i < laneNodes; i++) {
      // A bay may name and mark each stall for itself, so the chargers and the places to stand
      // afterwards can be one area rather than two at opposite ends of the loop.
      const spec = typeof extras === 'function' ? extras(i) : extras;
      const { code, ...marks } = spec;
      p.straight(far / laneNodes, {}, far / laneNodes);
      const made = p.path[p.path.length - 1];
      if (code) {
        // Rename the node the pen just dropped to the name this stall was given.
        const n = byCode.get(made);
        byCode.delete(made); n.code = code; byCode.set(code, n);
        for (const s of segments) { if (s.from === made) s.from = code; if (s.to === made) s.to = code; }
        p.path[p.path.length - 1] = code;
      }
      Object.assign(byCode.get(code ?? made), marks);
      laneCodes.push(code ?? made);
    }
  }
  p.turn(radius, 90);
  p.straight(depth - 2 * radius, {}, (depth - 2 * radius));
  p.turn(radius, -90);
  // Where the bay comes back is a merge: two routes arrive at this one node, the main line and the
  // lane. Saying so is what makes the controller keep the order they asked in -- without the mark,
  // a contested node goes to whoever asks at the right moment, and a vehicle on the quieter branch
  // can wait indefinitely while the node turns over to others.
  //
  // A two-hour run at 75 vehicles is what found it: one vehicle wanted the same rejoin for three
  // and a half minutes while the vehicle holding it had held it for forty-five seconds. At 25
  // vehicles nobody waited long enough to notice.
  //
  // The diverge is not marked. Both routes leave it from the same side, so it is a queue rather
  // than a merge, and marking it would make every vehicle entering a bay ask for a turn it is
  // already in line for.
  byCode.get(path[dIndex]).mergePoint = true;
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
  // A bay's curves bow the other way from the main line's. The track is the same track either way
  // -- the nodes do not move -- but the turn into a bay then reads as a turn away from the line
  // rather than as a bulge back towards it, which is how a turnout is drawn.
  for (let i = ownFirst; i < segments.length; i++)
    if (segments[i].curveMm) segments[i].curveMm = -segments[i].curveMm;
  return { lane: laneCodes, path };
}

/**
 * Spreads a side's bays along its straight run with even room between them.
 *
 * <p>A side is either `bays` of one kind, or `each`: a list of bays laid out in order, which is
 * what lets one side carry a long bay and a short one without pretending they are the same.
 */
function planBays(runs, sides, prefix) {
  const plan = [];
  let numbered = 0;
  sides.forEach((side, i) => {
    if (!runs[i]) return;
    const [from, to] = runs[i];
    const list = side.each ?? Array.from({ length: side.bays }, () => side);
    const room = to - from;
    const taken = list.reduce((sum, bay) => sum + bay.span, 0);
    const gap = Math.max(1, Math.floor((room - taken) / (list.length + 1)));
    let at = from + gap;
    for (const bay of list) {
      if (at + bay.span > to) continue;       // it would reach into the corner
      plan.push({
        at, span: bay.span, depth: bay.depth,
        label: bay.label ?? `${prefix}${String(++numbered).padStart(2, '0')}`,
        extras: bay.extras, laneNodes: bay.laneNodes,
      });
      at += bay.span + gap;
    }
  });
  return plan;
}

/* ================= OHT: two intrabay loops, joined down one side, with bays off each ============ */
/**
 * Two long loops, one above the other, with their work hanging off them, and a link between them
 * at one end. That is the shape a fab's overhead track is: a bay is a row of tools and the rail
 * goes up one side of it and back down the other, so the loop is long and thin and sits over the
 * row. The link at the end is what makes it one network rather than two -- a carrier that has to
 * cross from one bay to the next goes round by it.
 *
 * <p>A single ring round the whole floor is the other way to draw this, and it is worse for the
 * same reason the real thing is not drawn that way: every bay would be on the circumference, so the
 * distance between two of them would be set by how far apart they happen to sit on the ring rather
 * than by how far apart the tools are.
 */
const ohtTop = oval('OHT-A', 40000, 40000, OHT_RUN + 56000, 56000);
const ohtLow = oval('OHT-C', 40000, 168000, OHT_RUN + 56000, 56000);
let ohtTopPath = ohtTop.path, ohtLowPath = ohtLow.path;

// An oval's two straights are its upper and its lower, and a bay hangs outward from whichever it
// is on. The gap between the two loops therefore belongs to the lower loop's bays and to the link:
// everything on the upper loop goes up and away, so nothing reaches into a space it cannot see
// across.
const topPlan = planBays(ohtTop.runs, [
  { each: [
    ...repeat(SCALE, [
      { span: 4, depth: 26000, laneNodes: 8 },
      { span: 4, depth: 26000, laneNodes: 8 },
    ]),
    // One inspection spur, at the end of the line, however long the line is.
    { span: 4, depth: 30000, label: 'OHT-INSP', laneNodes: 3 },
  ] },
  { bays: 0, span: 4, depth: 0 },
], 'OHT-B');
const lowPlan = planBays(ohtLow.runs, [
  { bays: 2 * SCALE, span: 4, depth: 24000, laneNodes: 8 },
  { each: [
    ...repeat(SCALE, [{ span: 4, depth: 26000, laneNodes: 8 }]),
    { span: 4, depth: 26000, label: 'OHT-WAIT', extras: { waitFor: 'OHT' }, laneNodes: 2 },
  ] },
], 'OHT-D');

const ohtLanes = new Map();
for (const plan of topPlan.slice().sort((x, y) => y.at - x.at)) {
  const made = spur(ohtTopPath, plan.at, plan.span, plan.depth, plan.label,
    plan.extras ?? {}, plan.laneNodes ?? 1);
  ohtTopPath = made.path;
  ohtLanes.set(plan.label, made.lane);
}
for (const plan of lowPlan.slice().sort((x, y) => y.at - x.at)) {
  const made = spur(ohtLowPath, plan.at, plan.span, plan.depth, plan.label,
    plan.extras ?? {}, plan.laneNodes ?? 1);
  ohtLowPath = made.path;
  ohtLanes.set(plan.label, made.lane);
}

/**
 * The link between the two loops: down one side, up the other, clear of both.
 *
 * <p>Two one-way runs rather than one: a vehicle has to be able to come back, and a single length
 * of track that both directions used would be a place two vehicles meet head on with nowhere to go.
 * They are taken from the far end of each loop, where there is nothing else, and they are the only
 * track either loop shares with anything.
 */
function joinLoops(upper, lower) {
  const nodesOf = path => path.map(c => byCode.get(c)).filter(Boolean);
  const u = nodesOf(upper), l = nodesOf(lower);
  // Taken off the facing straights, not simply the rightmost nodes of each loop: the rightmost
  // are on the half-circle, and a line between two points of an arc cuts across the arc itself.
  const facing = (ns, y) => ns.filter(n => Math.abs(n.yMm - y) < 1).sort((a, b) => b.xMm - a.xMm);
  const down = facing(u, Math.max(...u.map(n => n.yMm)));   // the upper loop's lower straight
  const up = facing(l, Math.min(...l.map(n => n.yMm)));     // the lower loop's upper straight
  // One node apart, so the two runs are parallel rather than on top of one another.
  link(down[0].code, up[0].code);
  link(up[1].code, down[1].code);
  // Each loop receives the other's traffic at one node, which is a merge for the same reason a
  // bay's rejoin is: two streams, one node.
  up[0].mergePoint = true;
  down[1].mergePoint = true;
}
joinLoops(ohtTopPath, ohtLowPath);

// A port at every stall of a bay, not one per bay.
//
// A bay is a row of tools and every tool has a face the vehicle hands over at; one port per bay
// would be a floor with four places to deliver to. It is also what decides how much work a floor
// can carry: an order holds its two ports from the moment it is dispatched, so a layout with four
// ports can run two jobs at a time however many vehicles it has. The first load run on this layout
// queued forty-three of fifty orders on PORT_IN_USE, which is that arithmetic and nothing else.
let ohtPorts = 0;
[...topPlan, ...lowPlan].filter(p => /^OHT-[BD]/.test(p.label)).forEach((p, bay) => {
  (ohtLanes.get(p.label) ?? []).forEach(node => {
    ohtPorts++;
    ports.push({
      code: `BAY-O${String(bay + 1).padStart(2, '0')}-${String(ohtPorts).padStart(2, '0')}`,
      node, portType: ohtPorts % 2 === 1 ? 'LOAD' : 'UNLOAD', accessVehicleType: 'OHT',
    });
  });
});
const ohtPath = [...ohtTopPath, ...ohtLowPath];
const inspection = ohtLanes.get('OHT-INSP') ?? [];
if (inspection.length)
  zones.push({ code: 'INSPECTION', capacity: inspection.length, kind: 'MAINTENANCE', nodes: inspection });

/* ================= AGV: its own loop, bays, chargers and a waiting spur ======================== */
// Clear of the OHT ring's outermost bays: that ring now reaches further up and down than the old
// rectangle did, and an AGV bay off this loop's near side comes back towards it.
const agvRing = oval('AGV-M', 20000, 300000, AGV_RUN + 90000, 90000);
let agvPath = agvRing.path;
// Ten AGVs: four chargers and eight places to stand, in one bay. Fewer chargers than vehicles is
// ordinary and the controller queues for them, but a vehicle that has finished charging must have
// somewhere to go or the charger it stands on never comes free -- hence a stall for every vehicle
// and two over. They are in the same bay because stepping aside should cost a few metres: parking
// on the far side of the loop would spend more charge getting there than the charge was worth.
// One such bay per ten AGVs, numbered through: a stall is per vehicle, so this is the part of the
// floor that has to grow with the fleet rather than be subdivided more finely.
const stalls = group => i => i < 4
  ? { code: `AGV-CHG-${group * 4 + i + 1}`, chargePoint: true }
  : { code: `AGV-WAIT-${group * 8 + i - 3}`, waitFor: 'AGV' };
// An oval has two straights, so the bays are shared between them: work on the near side, the
// charging and parking area on the far one, where a vehicle standing is out of everybody's way.
const agvSides = [
  { bays: 3 * SCALE, span: 4, depth: 22000, laneNodes: 6 },
  { each: Array.from({ length: SCALE }, (_, g) => [
    { span: 8, depth: 26000, label: g ? `AGV-PARK-${g + 1}` : 'AGV-PARK',
      extras: stalls(g), laneNodes: 12 },
    { span: 4, depth: 22000, laneNodes: 6 },
  ]).flat() },
];
const agvPlan = planBays(agvRing.runs, agvSides, 'AGV-B');
const agvLanes = new Map();
for (const plan of agvPlan.slice().sort((x, y) => y.at - x.at)) {
  const made = spur(agvPath, plan.at, plan.span, plan.depth, plan.label,
    plan.extras ?? {}, plan.laneNodes ?? 1, 3000);
  agvPath = made.path;
  agvLanes.set(plan.label, made.lane);
}
let agvPorts = 0;
agvPlan.filter(p => p.label.startsWith('AGV-B')).forEach((p, bay) => {
  (agvLanes.get(p.label) ?? []).forEach(node => {
    agvPorts++;
    ports.push({
      code: `BAY-A${String(bay + 1).padStart(2, '0')}-${String(agvPorts).padStart(2, '0')}`,
      node, portType: agvPorts % 2 === 1 ? 'LOAD' : 'UNLOAD', accessVehicleType: 'AGV',
    });
  });
});

/* ================= the fleet ================= */
const chargeNodes = nodes.filter(n => n.chargePoint).map(n => n.code);
const onMain = path => path.filter(c => byCode.has(c));
const ohtMain = onMain(ohtPath), agvMain = onMain(agvPath);
// The fleet grows with the line, spread evenly round it as before.
const ohtFleet = 15 * SCALE, agvFleet = 10 * SCALE;
for (let i = 0; i < ohtFleet; i++)
  vehicles.push({ code: `OHT-${String(11 + i).padStart(2, '0')}`, name: `OHT ${11 + i}`, vType: 'OHT',
    initialNode: ohtMain[Math.floor(i * ohtMain.length / ohtFleet)], speedMmS: 2000, chargingPolicy: 'DISABLED' });
for (let i = 0; i < agvFleet; i++)
  vehicles.push({ code: `AGV-${String(11 + i).padStart(2, '0')}`, name: `AGV ${11 + i}`, vType: 'AGV',
    initialNode: agvMain[Math.floor(i * agvMain.length / agvFleet)], speedMmS: 1000,
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
