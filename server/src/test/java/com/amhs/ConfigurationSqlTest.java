package com.amhs;
import com.amhs.platform.*;
import com.amhs.fleet.*;
import com.amhs.topology.*;
import com.amhs.integration.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true","amhs.configuration.write-token=amhs-test-only-token-32-characters-long"})
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class ConfigurationSqlTest {
 private static final String TOKEN="Bearer amhs-test-only-token-32-characters-long";
 private static final String BASE="/api/v1/sites/1/configuration/";
 @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper json;
 @Autowired VehicleConfiguration vehicles; @Autowired PortConfiguration ports;
 @Autowired ConnectionConfiguration connections; @Autowired ProtocolConfiguration protocols; @Autowired CommunicationConfiguration communications;
 private VehicleSettings vehicle(String code){
  return new VehicleSettings(1,code,code,VehicleType.OHT,1100,1500,900,101L,102L,null,true,false,"CONTROLLER_MANAGED","REQUIRED","DISABLED");
 }
 private PortSettings port(String code){return new PortSettings(1,code,"LOAD","SYNTHETIC",VehicleType.OHT,101);}
 private long[] vda(String code){
  long v=vehicles.create(1,vehicle(code)).id();
  long c=connections.create(1,new ConnectionSettings("MQTT","BROKER","127.0.0.1",1883,false)).id();
  long p=protocols.create(1,new ProtocolSettings("VDA5050","3.0.0","draft-vda3")).id();
  return new long[]{v,c,p};
 }
 private CommunicationSettings binding(long[] ids,String serial){
  return new CommunicationSettings(ids[0],ids[1],ids[2],"TEST-MAKER","external-"+serial,serial,"vda5050",false);
 }
 private void rejected(Runnable call,int status,String code){
  assertThatThrownBy(call::run).isInstanceOfSatisfying(ConfigurationException.class,e->{assertThat(e.status()).isEqualTo(status);assertThat(e.getMessage()).isEqualTo(code);});
 }
 @Test void vehicleRoundTripAndApiVersionConflict()throws Exception{
  var response=mvc.perform(post(BASE+"vehicles").header("Authorization",TOKEN).contentType("application/json").content(json.writeValueAsBytes(vehicle("TEST-OHT"))))
    .andExpect(status().isCreated()).andReturn().getResponse();
  long id=json.readTree(response.getContentAsString()).path("id").asLong();
  var loaded=vehicles.get(1,id);
  assertThat(loaded.settings().homeNodeId()).isEqualTo(102L);
  assertThat(loaded.settings().widthMm()).isEqualTo(1100);
  mvc.perform(put(BASE+"vehicles/"+id+"?expectedVersion=0").header("Authorization",TOKEN).contentType("application/json").content(json.writeValueAsBytes(vehicle("TEST-OHT-EDIT"))))
    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
  mvc.perform(put(BASE+"vehicles/"+id+"?expectedVersion=0").header("Authorization",TOKEN).contentType("application/json").content(json.writeValueAsBytes(vehicle("STALE"))))
    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  assertThat(vehicles.get(1,id).settings().code()).isEqualTo("TEST-OHT-EDIT");
 }
 @Test void noTokenAndMalformedRequestsAreRejected()throws Exception{
  mvc.perform(post(BASE+"ports").contentType("application/json").content(json.writeValueAsBytes(port("TEST-P"))))
   .andExpect(status().isUnauthorized());
  mvc.perform(post(BASE+"ports").header("Authorization",TOKEN).contentType("application/json").content("{"))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"));
 }
 @Test void runningAndRecoveringRejectAllWrites(){
  for(String mode:List.of("RUNNING","RECOVERING")){
   jdbc.update("UPDATE platform.site_operation SET mode=? WHERE site_id=1",mode);
   rejected(()->vehicles.create(1,vehicle("BLOCKED")),409,"SITE_NOT_IN_MAINTENANCE");
   rejected(()->ports.update(1,1,0,port("BLOCKED")),409,"SITE_NOT_IN_MAINTENANCE");
   rejected(()->connections.create(1,new ConnectionSettings("TCP","CLIENT","127.0.0.1",1000,false)),409,"SITE_NOT_IN_MAINTENANCE");
   rejected(()->protocols.create(1,new ProtocolSettings("PC","1","draft")),409,"SITE_NOT_IN_MAINTENANCE");
   rejected(()->communications.create(1,new CommunicationSettings(1,1,1,"M","E",null,null,false)),409,"SITE_NOT_IN_MAINTENANCE");
  }
 }
 @Test void nodesAreScopedAndWrongVehicleNetworkIsRejected(){
  rejected(()->ports.create(1,new PortSettings(1,"OUTSIDE","L","S",VehicleType.OHT,999)),422,"NODE_OUTSIDE_REVISION");
  rejected(()->ports.create(1,new PortSettings(1,"WRONG","L","S",VehicleType.AGV,101)),422,"VEHICLE_TYPE_NETWORK_MISMATCH");
  var v=vehicle("WRONG-V");
  rejected(()->vehicles.create(1,new VehicleSettings(1,v.code(),v.name(),VehicleType.OHT,1,1,1,201,null,null,true,false,"CONTROLLER_MANAGED","DISABLED","DISABLED")),422,"VEHICLE_TYPE_NETWORK_MISMATCH");
 }
 @Test void fleetPreventsPortRetypingEvenWithoutOtherPorts(){
  jdbc.update("DELETE FROM topology.port WHERE site_id=1 AND access_vehicle_type='OHT'");
  rejected(()->ports.create(1,new PortSettings(1,"BAD","L","S",VehicleType.AGV,101)),422,"PORT_CONFLICTS_WITH_VEHICLE_NETWORK");
 }
 @Test void vdaIdentityAndProtocolCombinationAreValidated(){
  var ids=vda("VDA");
  rejected(()->communications.create(1,new CommunicationSettings(ids[0],ids[1],ids[2],"M","E",null,null,false)),422,"VDA_IDENTITY_REQUIRED");
  long tcp=connections.create(1,new ConnectionSettings("TCP","CLIENT","127.0.0.1",1500,false)).id();
  rejected(()->communications.create(1,new CommunicationSettings(ids[0],tcp,ids[2],"M","E","S","vda5050",false)),422,"UNSUPPORTED_PROTOCOL_COMBINATION");
  rejected(()->protocols.create(1,new ProtocolSettings("VDA5050","v3","draft")),422,"UNSUPPORTED_VDA_VERSION");
  rejected(()->connections.create(1,new ConnectionSettings("MQTT","CLIENT","127.0.0.1",1883,false)),422,"INVALID_CONNECTION_ROLE");
 }
 @Test void duplicateVdaIdentityIsRejectedAcrossConnectionProfiles()throws Exception{
  var first=vda("FIRST");communications.create(1,binding(first,"SERIAL"));
  var second=vda("SECOND");
  mvc.perform(post(BASE+"communications").header("Authorization",TOKEN).contentType("application/json").content(json.writeValueAsBytes(binding(second,"SERIAL"))))
   .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_CONFIGURATION"));
 }
 @Test void crossSiteReferencesFailAndUsedProfilesCannotChange(){
  var ids=vda("REFERENCES");communications.create(1,binding(ids,"S"));
  rejected(()->connections.update(1,ids[1],0,new ConnectionSettings("MQTT","BROKER","localhost",1884,false)),409,"PROFILE_IN_USE");
  rejected(()->protocols.update(1,ids[2],0,new ProtocolSettings("VDA5050","2.0.0","draft")),409,"PROFILE_IN_USE");
  jdbc.update("INSERT INTO platform.site VALUES(999,'TEST-OTHER','Other')");
  jdbc.update("INSERT INTO platform.site_operation VALUES(999,'MAINTENANCE')");
  rejected(()->communications.create(999,binding(ids,"OTHER")),404,"VEHICLE_NOT_FOUND");
 }
 @Test void unimplementedAdaptersCannotBeEnabledAndOhtCannotCharge(){
  rejected(()->connections.create(1,new ConnectionSettings("TCP","SERVER","127.0.0.1",1234,true)),422,"ADAPTER_NOT_IMPLEMENTED");
  var ids=vda("DISABLED");
  rejected(()->communications.create(1,new CommunicationSettings(ids[0],ids[1],ids[2],"M","E","S","vda5050",true)),422,"ADAPTER_NOT_IMPLEMENTED");
  rejected(()->vehicles.create(1,new VehicleSettings(1,"OHT-C","OHT-C",VehicleType.OHT,1,1,1,101,null,102L,true,false,"CONTROLLER_MANAGED","DISABLED","HOME")),422,"OHT_CHARGING_NOT_SUPPORTED");
 }
 @Test void databaseAlsoEnforcesSiteRevisionAndExternalUniqueness(){
  assertThatThrownBy(()->jdbc.update("UPDATE topology.port SET revision_id=999 WHERE id=1")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
 @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
 void concurrentUpdatesOnlyOneWins()throws Exception{
  var row=ports.create(1,port("CONCURRENT-"+UUID.randomUUID().toString().substring(0,8)));
  var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
  try{
   Callable<String> update=()->{barrier.await();try{ports.update(1,row.id(),0,row.settings());return "OK";}catch(ConfigurationException e){return e.getMessage();}};
   var first=pool.submit(update);var second=pool.submit(update);
   assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","VERSION_CONFLICT");
   assertThat(ports.get(1,row.id()).version()).isEqualTo(1);
  }finally{pool.shutdownNow();jdbc.update("DELETE FROM topology.port WHERE id=? AND site_id=1",row.id());}
 }
}
