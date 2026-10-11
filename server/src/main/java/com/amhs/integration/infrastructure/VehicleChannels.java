package com.amhs.integration.infrastructure;
import com.amhs.integration.DeviceChannel;
import com.amhs.integration.DeviceCommand;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * The way to a vehicle, whichever way that is.
 *
 * <p>A site is not all one kind of link: an OHT line on a TCP controller and AGVs on an MQTT broker
 * is an ordinary arrangement, and the command that goes to one must not have to know about the
 * other. Each adapter answers for the vehicles configuration has given it and says
 * ADAPTER_NOT_CONFIGURED for the rest; asking them in turn is therefore the whole of the routing,
 * and a vehicle nobody has wired comes back as not configured from the last of them, which is the
 * true answer.
 */
@Component("vehicleChannels")
class VehicleChannels implements DeviceChannel {
 private final List<DeviceChannel> adapters;
 VehicleChannels(JsonLineVehicleLinks tcp,MqttVehicleLinks mqtt,PlcVehicleLinks plc){ this.adapters=List.of(tcp,mqtt,plc); }

 @Override public Verdict send(DeviceCommand command){
  Verdict last=Verdict.notDelivered("ADAPTER_NOT_CONFIGURED");
  for(var adapter:adapters){
   var verdict=adapter.send(command);
   if(verdict==null) return Verdict.inDoubt("CHANNEL_RETURNED_NO_VERDICT");
   // "Not mine" is the only answer that passes to the next adapter. Anything else -- delivered,
   // refused, in doubt -- is this vehicle's link having answered, and is the answer.
   if(!"ADAPTER_NOT_CONFIGURED".equals(verdict.detail())) return verdict;
   last=verdict;
  }
  return last;
 }
}
