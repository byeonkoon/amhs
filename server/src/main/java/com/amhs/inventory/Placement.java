package com.amhs.inventory;
import java.time.OffsetDateTime;
/**
 * Where one carrier is while nothing is carrying it.
 *
 * <p>Exactly one of {@code slotId} and {@code portId} is set. A cell is a place somebody looked at
 * and recorded. A port means the carrier was handed over there and nobody has said which cell it
 * went into: the controller does not talk to the equipment's own storage, so that is the most it
 * can honestly claim, and it is not the same as having lost the carrier.
 *
 * <p>{@code reason} is how the person knows. It is required, because a record of where something is
 * that does not say how anybody found out is not worth more than a guess, and would be read as
 * though it were.
 */
public record Placement(long id,long siteId,String carrierCode,Long slotId,Long portId,
                        String evidence,String reason,String actorCode,OffsetDateTime placedAt,long version){
 /** True when the cell is known; false when all that is known is the port it was handed over at. */
 public boolean inACell(){ return slotId!=null; }
}
