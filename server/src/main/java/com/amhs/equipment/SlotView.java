package com.amhs.equipment;
/**
 * One cell.
 *
 * <p>{@code usable} says somebody decided this cell is not to be filled. It is not occupancy, not
 * a reservation and not where a carrier is: those are different facts with different lifetimes,
 * and a screen that shows them as one turns a blocked cell into an occupied one.
 */
public record SlotView(long id,long rackId,int level,int col,String code,boolean usable,String unusableReason){}
