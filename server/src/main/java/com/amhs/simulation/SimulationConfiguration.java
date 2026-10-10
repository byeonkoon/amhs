package com.amhs.simulation;
import com.amhs.equipment.SimulatedEquipment;
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
import org.springframework.beans.factory.annotation.Qualifier;
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
                                     TransportOrders orders,TopologyQuery topology,ObjectMapper json,
                                     SimulatedEquipment equipment,OperationOptionConfiguration options){
  return new SimulatedVehicles(fleet,traffic,outbox,ledger,orders,topology,json,equipment,options);
 }

 /**
  * Routes each command by its site's run mode. In SIMULATION it goes to the simulated vehicle, and
  * a vehicle not simulated is reported as not delivered. In OPERATION it goes to the device
  * adapter, which answers for the vehicles configuration has put on a link and says
  * ADAPTER_NOT_CONFIGURED for the rest.
  *
  * <p>The routing lives here because this is what makes a site simulated at all; the adapter is
  * taken as a plain dependency, so nothing in the simulator knows what it talks to.
  */
 @Bean @Primary
 DeviceChannel runModeDeviceChannel(SimulatedVehicles vehicles,OperationOptionConfiguration options,
                                    @Qualifier("vehicleChannels") DeviceChannel devices){
  return (DeviceCommand command)->{
   if(!options.get(command.siteId()).settings().simulated()) return devices.send(command);
   return vehicles.receive(command)
    ?DeviceChannel.Verdict.delivered()
    :DeviceChannel.Verdict.notDelivered("NOT_A_SIMULATED_VEHICLE");
  };
 }

 /**
  * Each tick, for every site in SIMULATION: put its vehicles on the track, run one sweep, then let
  * every simulated vehicle take its next transfer step. Between ticks, a finer motion tick (0.1 s) drives
  * the vehicles along their segments at their configured speed. A site that left SIMULATION stops
  * being simulated. On by default; {@code amhs.simulation.auto-run=false} turns it off (tests step
  * by hand).
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
     // The pass knows what a stopped site does -- nothing, except end the moves it had started.
     // The handover tick is asked separately, because a vehicle finishing a pickup while the plant
     // is stopped is the plant not being stopped.
     sweep.run(siteId,"simulator",null);
     if(sweep.progressing(siteId)) vehicles.settle(siteId);
     else vehicles.acknowledge(siteId);
    }catch(RuntimeException e){
     // One bad tick must not stop the loop; the next one sees the state as it then is.
     log.warn("simulation tick failed for site {}: {}",siteId,e.getMessage());
    }
   }
  }

  private long lastMotion=System.nanoTime();
  /** Drives for the time that actually passed, so a slow tick does not slow the vehicles down. */
  @Scheduled(fixedDelayString="${amhs.simulation.motion-ms:100}")
  void motion(){
   long now=System.nanoTime();
   double seconds=Math.min(1.0,(now-lastMotion)/1e9);
   lastMotion=now;
   // A stopped site keeps driving what is already rolling. Nothing new is granted while it is
   // stopped, so each vehicle runs out the authority it was holding and comes to a stand on a node
   // -- which is what stopping a plant means. Freezing them between two nodes instead would leave
   // the site in a state it can never leave: still holding road, with nothing able to release it.
   for(long siteId:options.simulatedSites()){
    try{ vehicles.drive(siteId,seconds); }
    catch(RuntimeException e){ log.warn("simulation motion failed for site {}: {}",siteId,e.getMessage()); }
   }
  }
 }
}
