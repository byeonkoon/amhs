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
                    String screen){
 /** How bad it is, in the only two degrees worth telling apart: act now, or see to it. */
 public enum Severity{ CRITICAL,WARNING }
}
