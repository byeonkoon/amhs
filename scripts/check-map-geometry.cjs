/*
 * Does this map's traffic control actually protect it?
 *
 * Reservations are granted per node. That is enough only while two routes share no space except
 * the nodes they both hold: a vehicle holding a node is safe from anything that must hold the same
 * node to get there. It stops being enough the moment two pieces of track pass through the same
 * place without sharing a node -- then the controller sees two vehicles on two different nodes and
 * allows both, and they are in the same place.
 *
 * So this reads a map file (or an export) and looks for exactly that:
 *   crossing   two segments whose paths come within the clearance and share no node
 *   swept      a node that another segment passes over, so a vehicle standing there is in the way
 *
 * Usage: node scripts/check-map-geometry.cjs <map.json> [clearanceMm]
 */
const fs = require('fs');

const file = process.argv[2];
const CLEAR = Number(process.argv[3] ?? 2000);   // half a vehicle plus a margin, mm
const SAMPLES = 32;

const doc = JSON.parse(fs.readFileSync(file, 'utf8'));
const at = new Map(doc.nodes.map(n => [n.code, n]));

/** The same shape the screens draw: a quadratic through a control point offset left of travel. */
function points(seg) {
  const a = at.get(seg.from), b = at.get(seg.to);
  const bow = seg.curveMm ?? 0;
  if (!bow) return [{ x: a.xMm, y: a.yMm }, { x: b.xMm, y: b.yMm }];
  const dx = b.xMm - a.xMm, dy = b.yMm - a.yMm, len = Math.hypot(dx, dy) || 1;
  const cx = (a.xMm + b.xMm) / 2 + (dy / len) * 2 * bow;
  const cy = (a.yMm + b.yMm) / 2 - (dx / len) * 2 * bow;
  const out = [];
  for (let i = 0; i <= SAMPLES; i++) {
    const t = i / SAMPLES, u = 1 - t;
    out.push({ x: u * u * a.xMm + 2 * u * t * cx + t * t * b.xMm,
               y: u * u * a.yMm + 2 * u * t * cy + t * t * b.yMm });
  }
  return out;
}
function distanceToSegment(p, a, b) {
  const dx = b.x - a.x, dy = b.y - a.y, len2 = dx * dx + dy * dy;
  const t = len2 ? Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2)) : 0;
  return Math.hypot(p.x - (a.x + dx * t), p.y - (a.y + dy * t));
}
// Endpoint distances alone miss crossings in the middle of long straight segments.
function intersects(a, b, c, d) {
  const cross = (p, q, r) => (q.x-p.x)*(r.y-p.y)-(q.y-p.y)*(r.x-p.x);
  const abC=cross(a,b,c), abD=cross(a,b,d), cdA=cross(c,d,a), cdB=cross(c,d,b);
  return Math.max(Math.min(a.x,b.x),Math.min(c.x,d.x)) <= Math.min(Math.max(a.x,b.x),Math.max(c.x,d.x))
    && Math.max(Math.min(a.y,b.y),Math.min(c.y,d.y)) <= Math.min(Math.max(a.y,b.y),Math.max(c.y,d.y))
    && ((abC<=0 && abD>=0)||(abC>=0 && abD<=0))
    && ((cdA<=0 && cdB>=0)||(cdA>=0 && cdB<=0));
}
function pathsIntersect(one, other) {
  for(let i=1;i<one.length;i++) for(let j=1;j<other.length;j++)
    if(intersects(one[i-1],one[i],other[j-1],other[j])) return true;
  return false;
}
function closest(one, other) {
  let best = Infinity;
  for (const p of one) for (let i = 0; i < other.length - 1; i++)
    best = Math.min(best, distanceToSegment(p, other[i], other[i + 1]));
  for (const p of other) for (let i = 0; i < one.length - 1; i++)
    best = Math.min(best, distanceToSegment(p, one[i], one[i + 1]));
  return best;
}

const drawn = doc.segments.map(s => ({ seg: s, pts: points(s) }));
const crossings = [], swept = [];

/**
 * Where a turnout leaves the line, the two routes necessarily run alongside each other for a few
 * metres: that is the fouling point, and what protects it is the junction node, which both routes
 * must hold to get there. So an approach that happens within FOUL of a junction both routes touch
 * is not a conflict -- provided no node sits inside that area, which the second check is about.
 */
const FOUL = 10000;
const touches = new Map();   // node -> segments that start or end there
for (const d of drawn) {
  for (const code of [d.seg.from, d.seg.to]) {
    if (!touches.has(code)) touches.set(code, []);
    touches.get(code).push(d.seg);
  }
}
const neighbours = new Map();
for (const s of doc.segments) {
  if (!neighbours.has(s.from)) neighbours.set(s.from, new Set());
  if (!neighbours.has(s.to)) neighbours.set(s.to, new Set());
  neighbours.get(s.from).add(s.to); neighbours.get(s.to).add(s.from);
}
function nearAJunctionOfBoth(a, b, point) {
  for (const [code, node] of at) {
    if (Math.hypot(point.x - node.xMm, point.y - node.yMm) > FOUL) continue;
    const near = new Set([code, ...(neighbours.get(code) ?? [])]);
    const onA = near.has(a.from) || near.has(a.to), onB = near.has(b.from) || near.has(b.to);
    if (onA && onB) return true;
  }
  return false;
}
function closestPoint(one, other) {
  let best = Infinity, where = null;
  for (const p of one) for (let i = 0; i < other.length - 1; i++) {
    const d = distanceToSegment(p, other[i], other[i + 1]);
    if (d < best) { best = d; where = p; }
  }
  return { gap: best, where };
}

for (let i = 0; i < drawn.length; i++)
  for (let j = i + 1; j < drawn.length; j++) {
    const a = drawn[i].seg, b = drawn[j].seg;
    // Segments that meet at a node are meant to touch there; the node is what serialises them.
    if (a.from === b.from || a.from === b.to || a.to === b.from || a.to === b.to) continue;
    const crossing = pathsIntersect(drawn[i].pts, drawn[j].pts);
    const gap = crossing ? 0 : closest(drawn[i].pts, drawn[j].pts);
    if (gap >= CLEAR) continue;
    const spot = closestPoint(drawn[i].pts, drawn[j].pts);
    if (!crossing && spot.where && nearAJunctionOfBoth(a, b, spot.where)) continue;
    crossings.push({ a: `${a.from}→${a.to}`, b: `${b.from}→${b.to}`, gap: Math.round(gap) });
  }

for (const node of doc.nodes)
  for (const { seg, pts } of drawn) {
    if (seg.from === node.code || seg.to === node.code) continue;
    let best = Infinity;
    for (let i = 0; i < pts.length - 1; i++)
      best = Math.min(best, distanceToSegment({ x: node.xMm, y: node.yMm }, pts[i], pts[i + 1]));
    if (best < CLEAR) swept.push({ node: node.code, seg: `${seg.from}→${seg.to}`, gap: Math.round(best) });
  }

/**
 * Track that cannot be driven round is a different kind of broken, and a one-way layout is where it
 * happens: a spur that rejoins facing the wrong way, or a run with no way back, leaves vehicles
 * that can reach a port but never leave it. Every node must be reachable from every other.
 */
const out = new Map(), into = new Map();
for (const s of doc.segments) {
  if (!out.has(s.from)) out.set(s.from, []);
  if (!into.has(s.to)) into.set(s.to, []);
  out.get(s.from).push(s.to); into.get(s.to).push(s.from);
}
function reach(from, edges) {
  const seen = new Set([from]), todo = [from];
  while (todo.length) {
    const here = todo.pop();
    for (const next of edges.get(here) ?? [])
      if (!seen.has(next)) { seen.add(next); todo.push(next); }
  }
  return seen;
}
// A site has one network per kind of vehicle -- rail and floor do not join -- so each piece of
// track is checked against its own, not against the whole map.
const both = new Map();
for (const s of doc.segments) {
  if (!both.has(s.from)) both.set(s.from, []);
  if (!both.has(s.to)) both.set(s.to, []);
  both.get(s.from).push(s.to); both.get(s.to).push(s.from);
}
const networks = [];
const placed = new Set();
for (const n of doc.nodes) {
  if (placed.has(n.code)) continue;
  const net = reach(n.code, both);
  for (const code of net) placed.add(code);
  networks.push(net);
}
const unreachable = [], noWayBack = [];
for (const net of networks) {
  const start = [...net][0];
  const forward = reach(start, out), backward = reach(start, into);
  for (const code of net) {
    if (!forward.has(code)) unreachable.push(`${code} (from ${start})`);
    if (!backward.has(code)) noWayBack.push(`${code} (to ${start})`);
  }
}
const deadEnds = doc.nodes.filter(n => !(out.get(n.code) ?? []).length).map(n => n.code);
console.log(`networks: ${networks.map(n => n.size).join(' + ')} nodes`);

const show = (title, rows, line) => {
  console.log(`\n${title}: ${rows.length}`);
  for (const r of rows.slice(0, 20)) console.log('  ' + line(r));
  if (rows.length > 20) console.log(`  … ${rows.length - 20} more`);
};
console.log(`${file}: nodes ${doc.nodes.length}, segments ${doc.segments.length}, clearance ${CLEAR}mm`);
show('Segments that pass within the clearance of one they share no node with', crossings,
  r => `${r.a}  ×  ${r.b}   gap ${r.gap}mm`);
show('Nodes another segment passes over', swept, r => `${r.node}  under  ${r.seg}   gap ${r.gap}mm`);
show('Nodes nothing leads to within their own network', unreachable, r => r);
show('Nodes with no way back within their own network', noWayBack, r => r);
show('Dead ends: nothing leads out', deadEnds, r => r);
process.exitCode = crossings.length || swept.length || unreachable.length || noWayBack.length || deadEnds.length ? 1 : 0;
