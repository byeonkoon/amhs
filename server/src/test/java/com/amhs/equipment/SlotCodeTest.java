package com.amhs.equipment;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * A cell's code is read by somebody standing in front of the rack, so it has to say which number is
 * the column without them knowing a convention.
 */
class SlotCodeTest {
 @Test void aCodeNamesTheRackThenTheColumnAndTheLevel(){
  assertThat(EquipmentConfiguration.slotCode("R-A",1,5)).isEqualTo("R-A 05/01");
  assertThat(EquipmentConfiguration.slotCode("R-A",8,20)).isEqualTo("R-A 20/08");
 }

 /**
  * Padding is a minimum, not a limit. A rack may be 200 columns wide, and the hundredth column is
  * the hundredth column -- a width that quietly cut it to two digits would name two cells the same.
  */
 @Test void aWideRackIsNotCutDownToTheWidthOfTheNarrowOnes(){
  assertThat(EquipmentConfiguration.slotCode("R-A",8,100)).isEqualTo("R-A 100/08");
  assertThat(EquipmentConfiguration.slotCode("R-A",60,200)).isEqualTo("R-A 200/60");
  // The pair that a truncating pad would collide: column 20 and column 200.
  assertThat(EquipmentConfiguration.slotCode("R-A",1,20))
   .isNotEqualTo(EquipmentConfiguration.slotCode("R-A",1,200));
 }
}
