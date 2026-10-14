package com.amhs.integration;

/**
 * Telling the system that asked for the work how it ended.
 *
 * <p>Orders arrive over REST and nothing went back. For work that finishes in a minute that is only
 * wasteful -- the caller polls. For work that stops and waits for a person it means nobody upstairs
 * is told, and the order sits there looking slow rather than stuck.
 */
public interface HostNotices {
 /**
  * Records that this order reached a state the system that asked for it should hear about.
  *
  * <p><b>Must run inside the caller's transaction</b>, next to the change it is about. A notice
  * written outside it would survive a rolled back transition and announce something that did not
  * happen; one written after it could be lost while the transition stood.
  *
  * <p>Repeating the same order and status writes nothing twice: the key is what makes the notice,
  * so a retried caller is safe and so is a transition that happens to be recorded again.
  *
  * @return true when a notice was written, false when that one was already there
  */
 boolean announce(long siteId,long orderId,String orderStatus,String reason);
}
