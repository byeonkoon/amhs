package com.amhs.transport;

/**
 * What a transition rests on, and where it establishes the carrier to be.
 *
 * <p>Moving on from a pickup or a drop changes where the carrier is, and that is the one thing the
 * ledger must not take on trust. Those transitions need evidence that says where it is now; a
 * transition that changes nothing about the carrier may rest on the system's own bookkeeping.
 *
 * @param kind            what kind of fact this is
 * @param commandId       the command the fact answers, when there is one
 * @param carrierLocation where the fact establishes the carrier to be; null when it says nothing
 */
public record TransitionEvidence(Kind kind,String commandId,CarrierLocation carrierLocation){
 public TransitionEvidence {
  if(kind==null) kind=Kind.SYSTEM;
  if(carrierLocation==CarrierLocation.UNKNOWN) carrierLocation=null;
 }

 public enum Kind {
  /** The controller's own bookkeeping: says nothing about the carrier. */
  SYSTEM,
  /** A load sensor changed state (carrier now present, or now absent). */
  SENSOR_TRANSITION,
  /** The vehicle reported the action it was given as finished. */
  ACTION_FEEDBACK,
  /** The vehicle finished but reported an error with it. */
  DONE_WITH_VEHICLE_ERROR,
  /** A person on site looked and confirmed. */
  MANUAL_CONFIRMED,
  /** A person decided how the order ends, after establishing where the carrier is. */
  OPERATOR_DECISION;

  /** Claims only a person can make, so they need a person behind them. */
  public boolean byPerson(){ return this==MANUAL_CONFIRMED||this==OPERATOR_DECISION; }
  /** Facts that can establish where the carrier is. */
  public boolean establishes(){ return this!=SYSTEM; }
 }

 public static TransitionEvidence system(){ return new TransitionEvidence(Kind.SYSTEM,null,null); }
 public static TransitionEvidence sensor(CarrierLocation location){ return new TransitionEvidence(Kind.SENSOR_TRANSITION,null,location); }
}
