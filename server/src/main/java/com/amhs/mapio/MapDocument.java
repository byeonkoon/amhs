package com.amhs.mapio;
import java.util.List;
/**
 * A whole site map as a file: the track, what is on it, and the fleet that runs on it.
 *
 * <p>Everything refers to everything else by <b>code</b>, never by id. Ids belong to the database
 * that happens to hold the map; a file written by hand, or on another system, or exported and
 * edited, has no business knowing them -- and a file full of ids silently means something different
 * once it is imported somewhere else.
 *
 * <p>Absent lists are empty, not an error: a map may legitimately have no zones, and a track being
 * drawn before its vehicles are decided is a normal state for a draft.
 */
public record MapDocument(List<Node> nodes,List<Segment> segments,List<Port> ports,
                          List<Vehicle> vehicles,List<Zone> zones){
 public MapDocument{
  nodes=nodes==null?List.of():List.copyOf(nodes);
  segments=segments==null?List.of():List.copyOf(segments);
  ports=ports==null?List.of():List.copyOf(ports);
  vehicles=vehicles==null?List.of():List.copyOf(vehicles);
  zones=zones==null?List.of():List.copyOf(zones);
 }
 /**
  * A place a vehicle can be. {@code waitFor} names the kind of vehicle that may stand here with
  * nothing to do; {@code chargePoint} is a place an AGV charges, and the two are never the same
  * node. Flags left out mean the ordinary answer: in use, not a stop node, not a junction.
  */
 public record Node(String code,Long xMm,Long yMm,Boolean enabled,Boolean stopNode,Boolean mergePoint,
                    Boolean chargePoint,String waitFor){}
 /**
  * One-way track from one node to another. {@code costMm} is what it costs to drive, defaulting to
  * the straight-line distance; {@code curveMm} is how far the middle of the segment bows off that
  * straight line, which is how a junction a rail vehicle can actually take is drawn.
  */
 public record Segment(String from,String to,Long costMm,Boolean enabled,Long curveMm){}
 /** A handover place on a node, and the kind of vehicle that may reach it. */
 public record Port(String code,String node,String portType,String accessVehicleType){}
 /** A vehicle and where it starts. {@code home} is where it waits, {@code charge} where it charges. */
 public record Vehicle(String code,String name,String vType,String initialNode,String home,String charge,
                       Integer speedMmS,String chargingPolicy,Boolean enabled,Boolean assignmentEnabled){}
 /**
  * An area that admits at most {@code capacity} vehicles. {@code kind} is TRAFFIC for an area
  * vehicles pass through and are moved out of, MAINTENANCE for one they are meant to stay in.
  */
 public record Zone(String code,Integer capacity,String kind,List<String> nodes){
  public Zone{ nodes=nodes==null?List.of():List.copyOf(nodes); }
 }
}
