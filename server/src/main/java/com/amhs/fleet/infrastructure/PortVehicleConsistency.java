package com.amhs.fleet.infrastructure;
import com.amhs.topology.PortChanging;
import com.amhs.platform.ConfigurationException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
@Component
class PortVehicleConsistency {
 private final JdbcTemplate jdbc;
 PortVehicleConsistency(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @EventListener
 public void validate(PortChanging event){
  var conflicts=jdbc.query("SELECT initial_node_id,home_node_id,charge_node_id FROM fleet.vehicle_profile WHERE site_id=? AND revision_id=? AND v_type<>?",
   (r,n)->event.component().contains(r.getLong(1)) || (r.getObject(2)!=null&&event.component().contains(r.getLong(2))) || (r.getObject(3)!=null&&event.component().contains(r.getLong(3))),
   event.siteId(),event.revisionId(),event.type().name());
  ConfigurationException.require(conflicts.stream().noneMatch(Boolean::booleanValue),"PORT_CONFLICTS_WITH_VEHICLE_NETWORK");
 }
}
