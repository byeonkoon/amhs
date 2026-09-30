package com.amhs.traffic;
/**
 * What a reported arrival changed.
 *
 * <p>{@code authorized} false means the vehicle reported a node it was not permitted to occupy.
 * Nothing is released in that case: the permission map and the physical world disagree, and
 * freeing nodes on the strength of a report we do not trust would hand them to someone else.
 */
public record Progress(long vehicleId, long nodeId, boolean authorized, int released, int stillHeld) {}
