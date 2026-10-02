package com.amhs.topology;
import com.amhs.platform.ConfigurationException;
/**
 * A segment's editable parameters. Its endpoints are what the segment is, so changing them is
 * rewiring rather than tuning and is not offered here.
 *
 * <p>{@code curveMm} bends the segment: how far its middle lies from the straight line between the
 * two nodes, positive to the left going from {@code fromNodeId} to {@code toNodeId}, negative to the
 * right. Zero is straight. It shapes the drawing and the path a vehicle follows; it does not change
 * the route cost, which stays what it was set to.
 */
public record EdgeSettings(long fromNodeId,long toNodeId,long cost,boolean enabled,Double curveMm){
 public static final double MAX_CURVE_MM=100_000;
 public EdgeSettings{ if(curveMm==null) curveMm=0.0; }
 public EdgeSettings(long fromNodeId,long toNodeId,long cost,boolean enabled){ this(fromNodeId,toNodeId,cost,enabled,0.0); }
 public void validate(){
  // Cost feeds route comparison, and a negative one would make the shortest path meaningless.
  ConfigurationException.require(cost>=0,"INVALID_COST");
  ConfigurationException.require(Double.isFinite(curveMm)&&Math.abs(curveMm)<=MAX_CURVE_MM,"INVALID_CURVE");
 }
}
