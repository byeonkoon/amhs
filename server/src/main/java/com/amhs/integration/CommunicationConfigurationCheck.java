package com.amhs.integration;

import com.amhs.fleet.VehicleConfiguration;
import com.amhs.platform.OperationOptionConfiguration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Read-only comparison of stored contracts, not an adapter activation or device health check. */
@Service
public class CommunicationConfigurationCheck {
 private final OperationOptionConfiguration options;
 private final VehicleConfiguration vehicles;
 private final CommunicationConfiguration communications;
 private final ConnectionConfiguration connections;
 private final ProtocolConfiguration protocols;
 private final VdaProfileConfiguration profiles;
 public CommunicationConfigurationCheck(OperationOptionConfiguration options,VehicleConfiguration vehicles,
   CommunicationConfiguration communications,ConnectionConfiguration connections,
   ProtocolConfiguration protocols,VdaProfileConfiguration profiles){
  this.options=options;this.vehicles=vehicles;this.communications=communications;
  this.connections=connections;this.protocols=protocols;this.profiles=profiles;
 }
 public record Issue(long vehicleId,String code){}
 public record Report(String scope,int vehiclesChecked,boolean consistent,boolean activationSupported,List<Issue> issues){}

 // All component reads must see the same configuration revision during concurrent edits.
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Report inspect(long site){
  var option=options.get(site).settings();
  var bindings=communications.list(site);
  var issues=new ArrayList<Issue>();
  int checked=0;
  for(var vehicle:vehicles.list(site)){
   if(!vehicle.settings().enabled())continue;
   checked++;
   long id=vehicle.id();
   var profileId=vehicle.settings().vdaProfileId();
   VdaProfileSettings profile=null;
   if(option.usesVda()){
    if(profileId==null)issues.add(new Issue(id,"VDA_PROFILE_REQUIRED"));
    else profile=profiles.get(site,profileId).settings();
   }else if(profileId!=null)issues.add(new Issue(id,"VDA_PROFILE_NOT_APPLICABLE"));
   var matches=bindings.stream().filter(b->b.settings().vehicleId()==id).toList();
   if(matches.isEmpty()){issues.add(new Issue(id,"COMMUNICATION_REQUIRED"));continue;}
   if(matches.size()!=1){issues.add(new Issue(id,"COMMUNICATION_AMBIGUOUS"));continue;}
   var binding=matches.getFirst().settings();
   var connection=connections.get(site,binding.connectionProfileId()).settings();
   var protocol=protocols.get(site,binding.protocolProfileId()).settings();
   String expectedTransport=option.usesVda()?"MQTT":"TCP";
   String expectedProtocol=option.usesVda()?"VDA5050":option.commType();
   if(!expectedTransport.equals(connection.commType())||!expectedProtocol.equals(protocol.protoType()))
    issues.add(new Issue(id,"SITE_COMMUNICATION_MISMATCH"));
   if(profile!=null){
    if(!profile.manufacturer().equals(binding.manufacturer())||!profile.interfaceName().equals(binding.interfaceName()))
     issues.add(new Issue(id,"VDA_IDENTITY_MISMATCH"));
    if(!profile.jsonVersion().equals(protocol.protoVersion()))issues.add(new Issue(id,"VDA_JSON_VERSION_MISMATCH"));
   }
  }
  return new Report("ENABLED_VEHICLE_COMMUNICATION",checked,issues.isEmpty(),false,List.copyOf(issues));
 }
}
