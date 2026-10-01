package com.amhs.transport;

/**
 * How far along its route a vehicle may be authorised in the order's current stage.
 *
 * <p>The route is costed and stored whole, approach and delivery together, but before the pickup a
 * vehicle is given road only as far as the pickup. The delivery leg is granted once the pickup has
 * been reported. Without that boundary the first movement could carry an empty vehicle past the
 * source towards the destination, and nothing in the command would say it was meant to stop there.
 */
final class MovementPhase {
 private MovementPhase(){}

 /**
  * The last route index the vehicle may be authorised to reach now, or -1 when the order is in a
  * stage where it is not meant to drive at all.
  *
  * @param pickupIndex where the pickup is in the route; negative when the assignment predates the
  *                    boundary, which leaves the whole route open as it was
  */
 static int lastIndex(OrderStatus status,int pickupIndex,int routeSize){
  if(routeSize==0||!status.movable()) return -1;
  if(status.beforePickup()&&pickupIndex>=0) return Math.min(pickupIndex,routeSize-1);
  return routeSize-1;
 }
}
