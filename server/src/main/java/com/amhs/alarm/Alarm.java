package com.amhs.alarm;
import java.time.OffsetDateTime;
/**
 * One thing that is true now and that somebody should do something about.
 *
 * <p>An alarm here is a condition, not an event. It is worked out from the site's current state
 * every time it is asked for, so it cannot say something is wrong after it has stopped being wrong,
 * and it cannot be missed because nobody was watching when it happened. A stored list of events
 * drifts from reality in both directions; this cannot.
 *
 * <p>{@code effect} is the part that makes an alarm worth reading. A line that says only what is
 * wrong leaves a person to work out whether it matters, and a screen full of those gets ignored.
 * This says what the condition is already causing -- work not being given out, a vehicle not being
 * moved, a result nobody can account for -- so the reader can tell at once whether to act.
 *
 * <p>{@code since} is a real timestamp from the thing itself, and {@code sinceMeans} says which
 * one, because "last reported at" and "started at" are different claims. When nothing in the data
 * says when the condition began, {@code since} is null rather than filled in with now: the
 * controller not knowing how long something has been true is itself worth seeing.
 */
public record Alarm(String key,Severity severity,String rule,
                    String subject,String summary,String effect,
                    OffsetDateTime since,String sinceMeans,
                    String screen,Seen seen,About about){
 /** How bad it is, in the only two degrees worth telling apart: act now, or see to it. */
 public enum Severity{ CRITICAL,WARNING }

 /**
  * That somebody has looked at this, and what they said about it. Null until anybody has.
  *
  * <p>It does not clear the alarm and cannot: the condition is still true, and a screen that lets
  * a person remove a true condition is how a real fault disappears. What it changes is only what
  * the next reader needs to do about it -- a room of people can tell a new fault from one already
  * being dealt with.
  */
 public record Seen(String reason,String actorCode,OffsetDateTime at){}

 /** A rule raises an alarm; whether anybody has looked at it is attached afterwards. */
 public Alarm(String key,Severity severity,String rule,String subject,String summary,String effect,
              OffsetDateTime since,String sinceMeans,String screen){
  this(key,severity,rule,subject,summary,effect,since,sinceMeans,screen,null,About.NOTHING);
 }
 public Alarm withSeen(Seen seen){
  return new Alarm(key,severity,rule,subject,summary,effect,since,sinceMeans,screen,seen,about);
 }
 /**
  * What the condition is about, as references rather than words: the vehicles, the order and the
  * command, so a screen can go from the alarm to the thing and back.
  */
 public record About(java.util.List<Long> vehicleIds,Long orderId,String commandId){
  public About { vehicleIds=vehicleIds==null?java.util.List.of():java.util.List.copyOf(vehicleIds); }
  public static final About NOTHING=new About(java.util.List.of(),null,null);
 }
 public Alarm about(java.util.List<Long> vehicleIds,Long orderId,String commandId){
  return new Alarm(key,severity,rule,subject,summary,effect,since,sinceMeans,screen,seen,new About(vehicleIds,orderId,commandId));
 }
 public Alarm aboutVehicle(Long vehicleId){
  return about(vehicleId==null?java.util.List.of():java.util.List.of(vehicleId),null,null);
 }
 public boolean acknowledged(){ return seen!=null; }
}
