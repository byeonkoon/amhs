package com.amhs.simulation;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleConfiguration;
import com.amhs.integration.CommandOutbox;
import com.amhs.integration.DeviceChannel;
import com.amhs.integration.DeviceCommand;
import com.amhs.platform.OperationOptionConfiguration;
import com.amhs.topology.TopologyQuery;
import com.amhs.traffic.TrafficControl;
import com.amhs.transport.SiteSweep;
import com.amhs.transport.TransportLedger;
import com.amhs.transport.TransportOrders;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.HashSet;

/**
 * The in-server simulator. Which sites it drives is the site's run mode option, not a start-up flag:
 * a site in SIMULATION has every command carried out by simulated vehicles, a site in OPERATION has
 * its commands go to device adapters and nothing simulated.
 */
@Configuration(proxyBeanMethods=false)
class SimulationConfiguration {
 private static final Logger log=LoggerFactory.getLogger(SimulationConfiguration.class);

 @Bean
 SimulatedVehicles simulatedVehicles(FleetState fleet,TrafficControl traffic,CommandOutbox outbox,TransportLedger ledger,
                                     TransportOrders orders,TopologyQuery topology,ObjectMapper json){
  return new SimulatedVehicles(fleet,traffic,outbox,ledger,orders,topology,json);
 }

 /**
  * Routes each command by its site's run mode. In SIMULATION it goes to the simulated vehicle, and
  * a vehicle not simulated is reported as not delivered. In OPERATION it goes to the device
  * adapters; none is implemented yet, so nothing leaves and the command stays retryable.
  */
 @Bean @Primary
 DeviceChannel runModeDeviceChannel(SimulatedVehicles vehicles,OperationOptionConfiguration options){
  return (DeviceCommand command)->{
   if(!options.get(command.siteId()).settings().simulated())
    return DeviceChannel.Verdict.notDelivered("ADAPTER_NOT_IMPLEMENTED");
   return vehicles.receive(command)
    ?DeviceChannel.Verdict.delivered()
    :DeviceChannel.Verdict.notDelivered("NOT_A_SIMULATED_VEHICLE");
  };
 }

 /**
  * Each tick, for every site in SIMULATION: put its vehicles on the track, run one sweep, then move
  * every simulated vehicle one step. A site that left SIMULATION stops being simulated. On by
  * default; {@code amhs.simulation.auto-run=false} turns it off (tests step by hand).
  */
 @Configuration(proxyBeanMethods=false)
 @EnableScheduling
 @ConditionalOnProperty(name="amhs.simulation.auto-run",havingValue="true",matchIfMissing=true)
 static class Loop {
  private final SiteSweep sweep; private final SimulatedVehicles vehicles; private final OperationOptionConfiguration options;
  private final VehicleConfiguration configuration;
  Loop(SiteSweep sweep,SimulatedVehicles vehicles,OperationOptionConfiguration options,VehicleConfiguration configuration){
   this.sweep=sweep; this.vehicles=vehicles; this.options=options; this.configuration=configuration;
  }
  @Scheduled(fixedDelayString="${amhs.simulation.tick-ms:1500}")
  void tick(){
   var simulated=new HashSet<>(options.simulatedSites());
   vehicles.clearExcept(simulated);
   for(long siteId:simulated){
    try{
     vehicles.placeAll(siteId,configuration);
     sweep.run(siteId,"simulator",null);
     vehicles.step(siteId);
    }catch(RuntimeException e){
     // One bad tick must not stop the loop; the next one sees the state as it then is.
     log.warn("simulation tick failed for site {}: {}",siteId,e.getMessage());
    }
   }
  }
 }
}
