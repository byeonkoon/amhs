package com.amhs.simulation;
import com.amhs.fleet.FleetState;
import com.amhs.integration.CommandOutbox;
import com.amhs.integration.DeviceChannel;
import com.amhs.integration.DeviceCommand;
import com.amhs.topology.TopologyQuery;
import com.amhs.traffic.TrafficControl;
import com.amhs.transport.SiteSweep;
import com.amhs.transport.TransportLedger;
import com.amhs.transport.TransportOrders;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The in-server simulator, present only when {@code amhs.simulation.enabled=true}.
 *
 * <p>Off by default and never meant for a site with real vehicles: while it is on, every command
 * goes to simulated vehicles, and one that is not simulated is reported as not delivered rather
 * than sent anywhere.
 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="amhs.simulation.enabled",havingValue="true")
class SimulationConfiguration {
 private static final Logger log=LoggerFactory.getLogger(SimulationConfiguration.class);

 @Bean
 SimulatedVehicles simulatedVehicles(FleetState fleet,TrafficControl traffic,CommandOutbox outbox,TransportLedger ledger,
                                     TransportOrders orders,TopologyQuery topology,ObjectMapper json){
  return new SimulatedVehicles(fleet,traffic,outbox,ledger,orders,topology,json);
 }

 /** Takes precedence over the stand-in channel; a command for a vehicle nobody placed goes nowhere. */
 @Bean @Primary
 DeviceChannel simulatedDeviceChannel(SimulatedVehicles vehicles){
  return (DeviceCommand command)->vehicles.receive(command)
   ?DeviceChannel.Verdict.delivered()
   :DeviceChannel.Verdict.notDelivered("NOT_A_SIMULATED_VEHICLE");
 }

 /**
  * Runs the site by itself: a sweep (assign, restart, extend, move out of zones, publish), then one
  * step of every simulated vehicle. Off unless {@code amhs.simulation.auto-run=true}; a test or an
  * operator can step it by hand instead.
  */
 @Configuration(proxyBeanMethods=false)
 @EnableScheduling
 @ConditionalOnProperty(name="amhs.simulation.auto-run",havingValue="true")
 static class Loop {
  private final SiteSweep sweep; private final SimulatedVehicles vehicles; private final long siteId;
  Loop(SiteSweep sweep,SimulatedVehicles vehicles,@Value("${amhs.simulation.site-id:1}") long siteId){
   this.sweep=sweep; this.vehicles=vehicles; this.siteId=siteId;
  }
  @Scheduled(fixedDelayString="${amhs.simulation.tick-ms:1000}")
  void tick(){
   try{
    sweep.run(siteId,"simulator",null);
    vehicles.step(siteId);
   }catch(RuntimeException e){
    // One bad tick must not stop the loop; the next one sees the state as it then is.
    log.warn("simulation tick failed: {}",e.getMessage());
   }
  }
 }
}
