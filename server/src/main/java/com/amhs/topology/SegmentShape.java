package com.amhs.topology;

/**
 * The shape of a segment between two node positions: straight, or bent by {@code curveMm} (see
 * {@link EdgeSettings}). A bent segment is a quadratic curve through the point {@code curveMm} away
 * from the middle of the straight line, on the side of {@code (dy, -dx)}: the left as drawn on the
 * plan (y growing downwards) when going from the first node to the second. The drawing and the
 * simulator use this same shape, so a vehicle is shown on the line it is drawn on.
 */
public record SegmentShape(double ax, double ay, double bx, double by, double curveMm) {
 private static final int SAMPLES = 32;

 /** The segment from a to b, found in either direction in the map. Null when the map has neither. */
 public static SegmentShape between(MapSnapshot map, long fromNodeId, long toNodeId) {
  MapSnapshot.Node a = null, b = null;
  for (var n : map.nodes()) { if (n.id() == fromNodeId) a = n; if (n.id() == toNodeId) b = n; }
  if (a == null || b == null) return null;
  for (var e : map.edges()) {
   if (e.fromNodeId() == fromNodeId && e.toNodeId() == toNodeId) return new SegmentShape(a.x(), a.y(), b.x(), b.y(), e.curveMm());
   // The same bend seen from the other end lies on the other side of the direction of travel.
   if (e.fromNodeId() == toNodeId && e.toNodeId() == fromNodeId) return new SegmentShape(a.x(), a.y(), b.x(), b.y(), -e.curveMm());
  }
  return new SegmentShape(a.x(), a.y(), b.x(), b.y(), 0);
 }

 private double[] control() {
  double dx = bx - ax, dy = by - ay, len = Math.hypot(dx, dy);
  double mx = (ax + bx) / 2, my = (ay + by) / 2;
  if (len == 0 || curveMm == 0) return new double[] { mx, my };
  // A quadratic curve passes its control point's direction at half the distance, hence twice.
  return new double[] { mx + dy / len * 2 * curveMm, my - dx / len * 2 * curveMm };
 }

 /** The point a fraction {@code t} (0..1) of the way along by parameter. */
 public double[] at(double t) {
  double[] c = control();
  double u = 1 - t;
  return new double[] { u * u * ax + 2 * u * t * c[0] + t * t * bx, u * u * ay + 2 * u * t * c[1] + t * t * by };
 }

 /** Length along the shape, mm. */
 public double length() {
  if (curveMm == 0) return Math.hypot(bx - ax, by - ay);
  double total = 0; double[] prev = at(0);
  for (int i = 1; i <= SAMPLES; i++) {
   double[] p = at((double) i / SAMPLES);
   total += Math.hypot(p[0] - prev[0], p[1] - prev[1]);
   prev = p;
  }
  return total;
 }

 /** The point {@code distance} mm along the shape from its start, clamped to its ends. */
 public double[] atDistance(double distance) {
  if (curveMm == 0) {
   double len = Math.hypot(bx - ax, by - ay);
   double t = len == 0 ? 1 : Math.max(0, Math.min(1, distance / len));
   return new double[] { ax + (bx - ax) * t, ay + (by - ay) * t };
  }
  double walked = 0; double[] prev = at(0);
  for (int i = 1; i <= SAMPLES; i++) {
   double[] p = at((double) i / SAMPLES);
   double step = Math.hypot(p[0] - prev[0], p[1] - prev[1]);
   if (walked + step >= distance) {
    double f = step == 0 ? 0 : (distance - walked) / step;
    return new double[] { prev[0] + (p[0] - prev[0]) * f, prev[1] + (p[1] - prev[1]) * f };
   }
   walked += step; prev = p;
  }
  return new double[] { bx, by };
 }
}
