package com.amhs;
import com.amhs.integration.*;
import com.amhs.integration.mapping.MappingLibrary;
import com.amhs.platform.ConfigurationException;
import com.amhs.platform.VehicleType;
import com.amhs.fleet.*;
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

/**
 * Mapping documents a site installs for itself.
 *
 * <p>What matters here is not that a row is written. It is that a document is read and judged
 * before it is stored, that a site's own document displaces the one shipped with the server, and
 * that one a vehicle still names cannot be taken away underneath it.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true","amhs.configuration.write-token=amhs-test-only-token-32-characters-long"})
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class MessageMappingSqlTest {
 private static final String TOKEN="Bearer amhs-test-only-token-32-characters-long";
 private static final String BASE="/api/v1/sites/1/configuration/mappings";
 @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc;
 @Autowired MappingConfiguration mappings; @Autowired MappingLibrary library;
 @Autowired VehicleConfiguration vehicles; @Autowired ConnectionConfiguration connections;
 @Autowired ProtocolConfiguration protocols; @Autowired CommunicationConfiguration communications;

 @BeforeEach void maintenance(){
  jdbc.update("UPDATE platform.site_operation SET mode='MAINTENANCE' WHERE site_id=1");
  library.forget(1);
 }
 @AfterEach void forget(){ library.forget(1); }

 /** A small but complete line-protocol document, named by the caller. */
 private static String document(String code,String moveTopic){
  return """
   {"mapping":"%s","adapter":"JSON_LINE_V1","version":1,
    "notes":"test",
    "identity":{"fromField":"vehicle"},
    "outbound":{"MOVE":{"topic":"%s","body":{"type":"move","to":{"from":"command.payload.to"}}}},
    "inbound":[{"when":{"topic":"state"},"report":{"nodeCode":{"from":"body.at"}}}]}
   """.formatted(code,moveTopic);
 }

 @Test void installedDocumentIsInUseForThatSiteAndIsListed()throws Exception{
  var saved=mappings.save(1,document("SITE-LINE","move"),"tester");
  assertThat(saved.code()).isEqualTo("SITE-LINE");
  assertThat(saved.adapter()).isEqualTo("JSON_LINE_V1");
  assertThat(saved.commands()).isEqualTo(1);
  assertThat(saved.inboundRules()).isEqualTo(1);
  assertThat(saved.updatedBy()).isEqualTo("tester");
  assertThat(saved.version()).isZero();

  assertThat(library.has(1,"SITE-LINE")).isTrue();
  assertThat(library.has(2,"SITE-LINE")).isFalse();
  assertThat(library.inUse(1)).containsEntry("SITE-LINE","JSON_LINE_V1");

  mvc.perform(get(BASE).header("Authorization",TOKEN)).andExpect(status().isOk())
   .andExpect(jsonPath("$[?(@.code=='SITE-LINE')].adapter").value("JSON_LINE_V1"));
  mvc.perform(get(BASE+"/available").header("Authorization",TOKEN)).andExpect(status().isOk())
   .andExpect(jsonPath("$['SITE-LINE']").value("JSON_LINE_V1"))
   .andExpect(jsonPath("$['VDA5050-V3']").value("MQTT_JSON_V1"));
 }

 @Test void savingAgainReplacesTheDocumentOfThatNameAndCountsAsANewVersion(){
  mappings.save(1,document("SITE-LINE","move"),"first");
  var again=mappings.save(1,document("SITE-LINE","drive"),"second");
  assertThat(again.version()).isEqualTo(1);
  assertThat(again.updatedBy()).isEqualTo("second");
  assertThat(mappings.list(1)).filteredOn(m->m.code().equals("SITE-LINE")).hasSize(1);
  assertThat(library.byCode(1,"SITE-LINE").orElseThrow().outbound().get("MOVE").topic()).isEqualTo("drive");
 }

 /** The site's own document of a shipped name wins; forgetting it brings the shipped one back. */
 @Test void aSiteDocumentDisplacesTheOneShippedWithTheServer(){
  assertThat(library.byCode(1,"VDA5050-V3").orElseThrow().adapter()).isEqualTo("MQTT_JSON_V1");
  mappings.save(1,document("VDA5050-V3","own"),"tester");
  assertThat(library.byCode(1,"VDA5050-V3").orElseThrow().adapter()).isEqualTo("JSON_LINE_V1");
  assertThat(library.byCode(2,"VDA5050-V3").orElseThrow().adapter()).isEqualTo("MQTT_JSON_V1");
  mappings.remove(1,"VDA5050-V3","tester");
  assertThat(library.byCode(1,"VDA5050-V3").orElseThrow().adapter()).isEqualTo("MQTT_JSON_V1");
 }

 @Test void aDocumentThatCannotBeReadIsRefusedWithWhatIsWrongAndNothingIsStored()throws Exception{
  assertThatThrownBy(()->mappings.save(1,"{ not json","tester"))
   .isInstanceOf(ConfigurationException.class).hasMessageContaining("MAPPING_NOT_JSON");
  // A PLC document without the handshake that makes a register write mean something.
  var broken="""
   {"mapping":"BROKEN","adapter":"PLC_WORD_V1","version":1,
    "area":{"write":"40100","read":"40200","words":8},
    "layout":{"command":{"at":0,"type":"u16"}},
    "outbound":{"MOVE":{"slots":{"command":1}}}}
   """;
  assertThatThrownBy(()->mappings.save(1,broken,"tester"))
   .isInstanceOf(ConfigurationException.class).hasMessageContaining("MAPPING_REJECTED");
  assertThat(mappings.list(1)).extracting(MappingConfiguration.Installed::code).doesNotContain("BROKEN");
  assertThat(library.has(1,"BROKEN")).isFalse();

  mvc.perform(post(BASE).header("Authorization",TOKEN).contentType("application/json").content(broken))
   .andExpect(status().isUnprocessableEntity());
 }

 @Test void aDocumentAVehicleStillNamesCannotBeTakenAway(){
  mappings.save(1,document("SITE-LINE","move"),"tester");
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=false WHERE site_id=1");
  var v=vehicles.create(1,new VehicleSettings(1,"MAP-V","MAP-V",VehicleType.OHT,1100,1500,900,101L,102L,null,false,false,
   "CONTROLLER_MANAGED","DISABLED","DISABLED",null));
  var c=connections.create(1,new ConnectionSettings("TCP","CLIENT","127.0.0.1",2099,false));
  var p=protocols.create(1,new ProtocolSettings("PC","1","SITE-LINE"));
  var link=communications.create(1,new CommunicationSettings(v.id(),c.id(),p.id(),"M","MAP-V",null,null,false));

  assertThatThrownBy(()->mappings.remove(1,"SITE-LINE","tester"))
   .isInstanceOf(ConfigurationException.class).hasMessageContaining("MAPPING_IN_USE");
  assertThat(library.has(1,"SITE-LINE")).isTrue();

  // Pointing the vehicle at another dialect is what releases it; the row that names it is the
  // thing to change first, which is the whole reason the refusal is there.
  var other=protocols.create(1,new ProtocolSettings("PC","1","SOMETHING-ELSE"));
  communications.update(1,link.id(),link.version(),
   new CommunicationSettings(v.id(),c.id(),other.id(),"M","MAP-V",null,null,false));
  mappings.remove(1,"SITE-LINE","tester");
  assertThat(library.has(1,"SITE-LINE")).isFalse();
 }

 /** Reading what is installed is open as every read here is; installing one is not. */
 @Test void aMappingIsAnAdministratorsChangeAndOnlyWhileTheSiteIsStopped()throws Exception{
  mvc.perform(get(BASE)).andExpect(status().isOk());
  mvc.perform(post(BASE).contentType("application/json").content(document("SITE-LINE","move")))
   .andExpect(status().isUnauthorized());
  jdbc.update("UPDATE platform.site_operation SET mode='RUNNING' WHERE site_id=1");
  assertThatThrownBy(()->mappings.save(1,document("SITE-LINE","move"),"tester"))
   .isInstanceOf(ConfigurationException.class).hasMessageContaining("SITE_NOT_IN_MAINTENANCE");
 }

 @Test void askingForOneThatIsNotInstalledSaysSo()throws Exception{
  assertThatThrownBy(()->mappings.get(1,"NOT-THERE"))
   .isInstanceOf(ConfigurationException.class).hasMessageContaining("MAPPING_NOT_FOUND");
  mvc.perform(get(BASE+"/NOT-THERE").header("Authorization",TOKEN)).andExpect(status().isNotFound());
 }
}
