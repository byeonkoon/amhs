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
class CommunicationConfigurationCheckSqlTest {
 private static final String TOKEN="Bearer amhs-test-only-token-32-characters-long";
 private static final String BASE="/api/v1/sites/1/configuration/";
 @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc;
 @Autowired OperationOptionConfiguration options; @Autowired VdaProfileConfiguration profiles; @Autowired VehicleConfiguration vehicles;
 @Autowired CommunicationConfigurationCheck communicationCheck;
 @Autowired CommunicationConfiguration communications;
 @Autowired ConnectionConfiguration connections;
 @Autowired ProtocolConfiguration protocols;

 private VehicleSettings vehicle(String code,boolean enabled,Long profile){
  return new VehicleSettings(1,code,code,VehicleType.OHT,1100,1500,900,101L,102L,null,enabled,false,"CONTROLLER_MANAGED","DISABLED","DISABLED",profile);
 }
 private VdaProfileSettings profile(String code,String topic,String json,String source,String dest){
  return new VdaProfileSettings(code,"vda5050",topic,"TEST-MAKER",json,source,dest);
 }
 @Test void communicationCheckReportsMissingProfilesWithoutChangingConfiguration()throws Exception{
  jdbc.update("UPDATE platform.site_operation SET comm_type='MQTT' WHERE site_id=1");
  var before=vehicles.list(1);
  var report=communicationCheck.inspect(1);
  assertThat(report.vehiclesChecked()).isGreaterThan(0);
  assertThat(report.consistent()).isFalse();
  assertThat(report.activationSupported()).isFalse();
  assertThat(report.issues()).extracting(CommunicationConfigurationCheck.Issue::code).contains("VDA_PROFILE_REQUIRED","COMMUNICATION_REQUIRED");
  assertThat(vehicles.list(1)).isEqualTo(before);
  mvc.perform(get(BASE+"communication-check")).andExpect(status().isOk())
   .andExpect(jsonPath("$.activationSupported").value(false));
  mvc.perform(get("/api/v1/sites/999999/configuration/communication-check")).andExpect(status().isNotFound());
 }
 @Test void communicationCheckComparesIdentityAndJsonWithoutActivatingAdapters(){
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=false WHERE site_id=1");
  jdbc.update("UPDATE platform.site_operation SET comm_type='MQTT' WHERE site_id=1");
  var p=profiles.create(1,profile("CHECK","v2","1.0.0","pick","drop"));
  var v=vehicles.create(1,vehicle("CHECK-V",true,p.id()));
  var c=connections.create(1,new ConnectionSettings("MQTT","BROKER","127.0.0.1",1883,false));
  var protocol=protocols.create(1,new ProtocolSettings("VDA5050","3.0.0","draft"));
  communications.create(1,new CommunicationSettings(v.id(),c.id(),protocol.id(),"OTHER","CHECK","SERIAL","other",false));
  assertThat(communicationCheck.inspect(1).issues()).extracting(CommunicationConfigurationCheck.Issue::code)
   .containsExactlyInAnyOrder("VDA_IDENTITY_MISMATCH","VDA_JSON_VERSION_MISMATCH");
  profiles.update(1,p.id(),p.version(),new VdaProfileSettings("CHECK","other","v2","OTHER","3.0.0","pick","drop"));
  var report=communicationCheck.inspect(1);
  assertThat(report.vehiclesChecked()).isEqualTo(1);
  assertThat(report.consistent()).isTrue();
  assertThat(report.activationSupported()).isFalse();
  assertThat(report.issues()).isEmpty();
  assertThat(connections.get(1,c.id()).settings().enabled()).isFalse();
 }
 @Test void communicationCheckReportsSiteProtocolMismatch(){
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=false WHERE site_id=1");
  var v=vehicles.create(1,vehicle("CHECK-PC",true,null));
  var c=connections.create(1,new ConnectionSettings("TCP","CLIENT","127.0.0.1",2001,false));
  var p=protocols.create(1,new ProtocolSettings("PLC","1","draft"));
  communications.create(1,new CommunicationSettings(v.id(),c.id(),p.id(),"M","CHECK-PC",null,null,false));
  assertThat(communicationCheck.inspect(1).issues()).extracting(CommunicationConfigurationCheck.Issue::code)
   .containsExactly("SITE_COMMUNICATION_MISMATCH");
 }
}
