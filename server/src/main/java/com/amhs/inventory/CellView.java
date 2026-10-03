package com.amhs.inventory;
import java.time.OffsetDateTime;
/**
 * One cell of a rack, with both of the independent things that can be true of it.
 *
 * <p>{@code usable} is configuration: somebody decided this cell is not to be filled, and why.
 * {@code carrierCode} is a fact about now: something is in it. They are not two values of one
 * state. A cell can be unusable and holding something -- a cell marked after a carrier was already
 * there, which is exactly the situation a person needs to see -- so this carries both and lets the
 * screen show both. Collapsing them into a single status is how a cell being kept empty on purpose
 * turns into a cell that is simply full, and the reason it was being kept empty disappears.
 */
public record CellView(long slotId,int level,int col,String code,
                       boolean usable,String unusableReason,
                       String carrierCode,OffsetDateTime placedAt){
 public boolean occupied(){ return carrierCode!=null; }
}
