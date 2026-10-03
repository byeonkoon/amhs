package com.amhs.inventory.api;
import com.amhs.inventory.CarrierPlacements;
import com.amhs.inventory.CellView;
import com.amhs.inventory.Placement;
import com.amhs.platform.ConfigurationException;
import com.amhs.platform.Principal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import java.util.List;
import java.util.Map;

/**
 * What is stored, and where.
 *
 * <p>Manager level: this is not a machine's parameters and not the track, it is what the site is
 * holding. The path says {@code inventory}, which is what the access gate reads to require that
 * level, so the rule and the address cannot drift apart.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/inventory")
class InventoryController {
 private final CarrierPlacements placements;
 InventoryController(CarrierPlacements placements){ this.placements=placements; }

 @GetMapping("/placements")
 List<Placement> placements(@PathVariable long siteId){ return placements.placements(siteId); }

 @GetMapping("/summary")
 CarrierPlacements.Summary summary(@PathVariable long siteId){ return placements.summary(siteId); }

 @GetMapping("/equipment/{unitId}/racks/{rackId}/cells")
 List<CellView> cells(@PathVariable long siteId,@PathVariable long unitId,@PathVariable long rackId){
  return placements.cells(siteId,unitId,rackId);
 }

 /**
  * Records where a carrier is: in a cell, or handed over at a port with the cell not known.
  *
  * <p>One call for both because they answer the same question with different precision, and because
  * recording a carrier into a cell it has moved to is the same act as recording it the first time.
  */
 @PutMapping("/placements/{carrierCode}")
 Placement place(@PathVariable long siteId,@PathVariable String carrierCode,@RequestBody Map<String,Object> body){
  String reason=str(body.get("reason"));
  Long slotId=id(body.get("slotId")); Long portId=id(body.get("portId"));
  if(slotId!=null){
   Long unitId=id(body.get("unitId")); Long rackId=id(body.get("rackId"));
   ConfigurationException.require(unitId!=null&&rackId!=null,"RACK_REQUIRED_FOR_CELL");
   return placements.placeInCell(siteId,carrierCode,unitId,rackId,slotId,reason,actor());
  }
  ConfigurationException.require(portId!=null,"CELL_OR_PORT_REQUIRED");
  return placements.recordAtPort(siteId,carrierCode,portId,reason,actor());
 }

 @DeleteMapping("/placements/{carrierCode}")
 Map<String,Boolean> remove(@PathVariable long siteId,@PathVariable String carrierCode,
                            @RequestParam(required=false) String reason){
  placements.remove(siteId,carrierCode,reason,actor());
  return Map.of("removed",true);
 }

 private static String str(Object v){ return v==null?null:String.valueOf(v); }
 private static Long id(Object v){
  if(v==null) return null;
  if(v instanceof Number n) return n.longValue();
  try{ return Long.valueOf(String.valueOf(v)); }
  catch(NumberFormatException e){ throw new ConfigurationException(422,"ID_NOT_A_NUMBER"); }
 }
 /** Who the access gate let through; a request that never passed it is just "api". */
 private static String actor(){
  var attributes=RequestContextHolder.getRequestAttributes();
  var principal=attributes==null?null:attributes.getAttribute(Principal.REQUEST_ATTRIBUTE,RequestAttributes.SCOPE_REQUEST);
  return principal instanceof Principal p?p.code():"api";
 }
}
