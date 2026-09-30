package com.amhs;
import com.amhs.platform.*;
import com.amhs.fleet.*;
import com.amhs.integration.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Site communication type in the operation option, and the VDA profile a vehicle may name. */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true","amhs.configuration.write-token=amhs-test-only-token-32-characters-long"})
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class OperationVdaProfileSqlTest {
 private static final String TOKEN="Bearer amhs-test-only-token-32-characters-long";
 private static final String BASE="/api/v1/sites/1/configuration/";
 @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc;
 @Autowired OperationOptionConfiguration options; @Autowired VdaProfileConfiguration profiles; @Autowired VehicleConfiguration vehicles;

 private VehicleSettings vehicle(String code,boolean enabled,Long profile){
  return new VehicleSettings(1,code,code,VehicleType.OHT,1100,1500,900,101L,102L,null,enabled,false,"CONTROLLER_MANAGED","DISABLED","DISABLED",profile);
 }
 private VdaProfileSettings profile(String code,String topic,String json,String source,String dest){
  return new VdaProfileSettings(code,"vda5050",topic,"TEST-MAKER",json,source,dest);
 }
 private void setComm(String commType){
  options.update(1,options.get(1).version(),new OperationOptions(commType,options.get(1).settings().nodeCount()));
 }
 private long sampleV3(){return profiles.list(1).stream().filter(p->p.settings().code().equals("SAMPLE_V3")).findFirst().orElseThrow().id();}
 private void rejected(Runnable call,int status,String code){
  assertThatThrownBy(call::run).isInstanceOfSatisfying(ConfigurationException.class,e->{assertThat(e.status()).isEqualTo(status);assertThat(e.getMessage()).isEqualTo(code);});
 }

 @Test void operationOptionDefaultsToPcWithTwentyNodes()throws Exception{
  mvc.perform(get(BASE+"operation")).andExpect(status().isOk())
   .andExpect(jsonPath("$.settings.commType").value("PC")).andExpect(jsonPath("$.settings.nodeCount").value(20));
 }
 @Test void operationOptionIsValidatedVersionedAndProtected()throws Exception{
  long v=options.get(1).version();
  rejected(()->options.update(1,v,new OperationOptions("TCP",20)),422,"INVALID_COMM_TYPE");
  rejected(()->options.update(1,v,new OperationOptions("PC",0)),422,"INVALID_NODE_COUNT");
  rejected(()->options.update(1,v,new OperationOptions("PC",101)),422,"INVALID_NODE_COUNT");
  var changed=options.update(1,v,new OperationOptions("PLC",35));
  assertThat(changed.settings()).isEqualTo(new OperationOptions("PLC",35));
  rejected(()->options.update(1,v,new OperationOptions("PC",20)),409,"VERSION_CONFLICT");
  mvc.perform(put(BASE+"operation?expectedVersion="+changed.version()).contentType("application/json").content("{\"commType\":\"PC\",\"nodeCount\":20}"))
   .andExpect(status().isUnauthorized());
  mvc.perform(put(BASE+"operation?expectedVersion="+changed.version()).header("Authorization",TOKEN).contentType("application/json").content("{\"commType\":\"MQTT\",\"nodeCount\":20}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.settings.commType").value("MQTT"));
 }
 @Test void runningSiteRejectsOptionChanges(){
  jdbc.update("UPDATE platform.site_operation SET mode='RUNNING' WHERE site_id=1");
  rejected(()->options.update(1,options.get(1).version(),new OperationOptions("MQTT",20)),409,"SITE_NOT_IN_MAINTENANCE");
 }
 @Test void topicVersionAndJsonVersionAreIndependent(){
  var seeded=profiles.list(1);
  assertThat(seeded).extracting(p->p.settings().code()).contains("SAMPLE_V3","SAMPLE_V2");
  var v2=profiles.create(1,profile("TEST_V2","v2","1.0.0","pick","drop"));
  assertThat(v2.settings().topicVersion()).isEqualTo("v2");
  assertThat(v2.settings().jsonVersion()).isEqualTo("1.0.0");
  var custom=profiles.create(1,profile("TEST_LIFT","v3","3.0.0","palletLift-cmd1","palletLift-cmd2"));
  assertThat(custom.settings().destAction()).isEqualTo("palletLift-cmd2");
 }
 @Test void malformedProfilesAreRejected(){
  rejected(()->profiles.create(1,profile("lower","v3","3.0.0","pick","drop")),422,"INVALID_VDA_PROFILE_CODE");
  rejected(()->profiles.create(1,new VdaProfileSettings("BAD_IF","vda/5050","v3","M","3.0.0","pick","drop")),422,"INVALID_INTERFACE_NAME");
  rejected(()->profiles.create(1,new VdaProfileSettings("BAD_MAKER","vda5050","v3","A#B","3.0.0","pick","drop")),422,"INVALID_MANUFACTURER");
  rejected(()->profiles.create(1,profile("BAD_TOPIC","3","3.0.0","pick","drop")),422,"INVALID_TOPIC_VERSION");
  rejected(()->profiles.create(1,profile("BAD_JSON","v3","v3","pick","drop")),422,"INVALID_JSON_VERSION");
  rejected(()->profiles.create(1,profile("BAD_LIST","v3","3.0.0","pick,drop","drop")),422,"INVALID_SOURCE_ACTION");
  rejected(()->profiles.create(1,profile("SAME","v3","3.0.0","pick","pick")),422,"VDA_ACTIONS_MUST_DIFFER");
 }
 @Test void pcOrPlcSiteKeepsVehicleProfileEmpty(){
  rejected(()->vehicles.create(1,vehicle("T-PC",true,sampleV3())),422,"VDA_PROFILE_NOT_APPLICABLE");
  assertThat(vehicles.create(1,vehicle("T-PC-OK",true,null)).settings().vdaProfileId()).isNull();
 }
 @Test void mqttSiteNeedsAnExistingProfileForVehiclesInUse(){
  setComm("MQTT");
  rejected(()->vehicles.create(1,vehicle("T-NONE",true,null)),422,"VDA_PROFILE_REQUIRED");
  assertThat(vehicles.create(1,vehicle("T-OFF",false,null)).settings().vdaProfileId()).isNull();
  rejected(()->vehicles.create(1,vehicle("T-MISSING",true,999_999L)),422,"VDA_PROFILE_NOT_FOUND");
  jdbc.update("INSERT INTO platform.site VALUES(998,'TEST-OTHER-VDA','Other')");
  jdbc.update("INSERT INTO platform.site_operation(site_id,mode) VALUES(998,'MAINTENANCE')");
  long foreign=profiles.create(998,profile("FOREIGN","v3","3.0.0","pick","drop")).id();
  rejected(()->vehicles.create(1,vehicle("T-FOREIGN",true,foreign)),422,"VDA_PROFILE_NOT_FOUND");
  var ok=vehicles.create(1,vehicle("T-VDA",true,sampleV3()));
  assertThat(ok.settings().vdaProfileId()).isEqualTo(sampleV3());
 }
 @Test void leavingMqttNeedsVehicleProfilesClearedFirst(){
  setComm("MQTT");
  var v=vehicles.create(1,vehicle("T-LEAVE",true,sampleV3()));
  rejected(()->setComm("PC"),409,"VDA_PROFILE_ASSIGNED");
  vehicles.update(1,v.id(),v.version(),vehicle("T-LEAVE",false,null));
  setComm("PC");
  assertThat(options.get(1).settings().commType()).isEqualTo("PC");
 }
 @Test void profileApiListsAndProtectsWrites()throws Exception{
  mvc.perform(get(BASE+"vda-profiles")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.settings.code=='SAMPLE_V2')].settings.jsonVersion").value("1.0.0"));
  mvc.perform(post(BASE+"vda-profiles").contentType("application/json")
   .content("{\"code\":\"API_V3\",\"interfaceName\":\"vda5050\",\"topicVersion\":\"v3\",\"manufacturer\":\"M\",\"jsonVersion\":\"3.0.0\",\"sourceAction\":\"pick\",\"destAction\":\"drop\"}"))
   .andExpect(status().isUnauthorized());
  mvc.perform(post(BASE+"vda-profiles").header("Authorization",TOKEN).contentType("application/json")
   .content("{\"code\":\"API_V3\",\"interfaceName\":\"vda5050\",\"topicVersion\":\"v3\",\"manufacturer\":\"M\",\"jsonVersion\":\"3.0.0\",\"sourceAction\":\"pick\",\"destAction\":\"drop\"}"))
   .andExpect(status().isCreated()).andExpect(jsonPath("$.settings.code").value("API_V3"));
 }
}
