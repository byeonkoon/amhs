package com.amhs;
import com.amhs.integration.mapping.MessageMapper;
import com.amhs.integration.mapping.DeviceMessageMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/**
 * A vehicle's dialect written down as a document rather than as code.
 *
 * <p>What is checked here is what a mapping is allowed to do and what it must refuse: it can take a
 * value from a named place, translate it through a table and write it under a name this controller
 * knows, and it cannot invent a value, pass through a word the table does not list, or produce a
 * report field nobody acts on. The sample shipped with the repository is read as part of it, so a
 * document that stops being valid is caught here rather than at a site.
 */
class MessageMappingTest {
 private final ObjectMapper json=new ObjectMapper();
 private final MessageMapper mapper=new MessageMapper(json);

 private DeviceMessageMapping read(String document) throws Exception {
  return DeviceMessageMapping.read(json,document);
 }
 private static final Map<String,String> IDENTITY=
  Map.of("prefix","uagv","version","v3","manufacturer","ACME","serial","AGV-7");
 private MessageMapper.Command move(String nodeIds) throws Exception {
  return new MessageMapper.Command("cmd-1","MOVE",42L,7,"AGV-7",3,"2026-10-10T23:00:00+09:00",
   json.readTree("{\"authorizedNodeIds\":"+nodeIds+"}"),IDENTITY);
 }

 @Test void theSampleShippedWithTheRepositoryIsAValidMapping() throws Exception {
  var document=Files.readString(Path.of("../docs/sample-mappings/vda5050-v3.json"));
  var mapping=read(document);
  assertThat(mapping.code()).isEqualTo("VDA5050-V3");
  assertThat(mapping.outbound()).containsKeys("MOVE","STOP");
  assertThat(mapping.inbound()).hasSize(2);
 }

 @Test void acommandBecomesTheMessageTheFarSideExpects() throws Exception {
  var mapping=read(Files.readString(Path.of("../docs/sample-mappings/vda5050-v3.json")));
  var message=mapper.toMessage(mapping,move("[203,204]")).orElseThrow();
  assertThat(message.topic()).isEqualTo("uagv/v3/ACME/AGV-7/order");
  assertThat(message.body().get("orderId").asText()).isEqualTo("cmd-1");
  assertThat(message.body().get("version").asText()).isEqualTo("3.0.0");
  assertThat(message.body().get("serialNumber").asText()).isEqualTo("AGV-7");
  // The list is mapped element by element, each one knowing where it stands in the run.
  var nodes=message.body().withArray("nodes");
  assertThat(nodes).hasSize(2);
  assertThat(nodes.get(0).get("nodeId").asText()).isEqualTo("203");
  assertThat(nodes.get(0).get("sequenceId").asInt()).isZero();
  assertThat(nodes.get(1).get("sequenceId").asInt()).isEqualTo(1);
  assertThat(nodes.get(0).get("released").asBoolean()).isTrue();
 }

 @Test void acommandTheMappingDoesNotDescribeProducesNothing() throws Exception {
  var mapping=read(Files.readString(Path.of("../docs/sample-mappings/vda5050-v3.json")));
  var unknown=new MessageMapper.Command("cmd-2","MORE",42L,7,"AGV-7",4,null,json.createObjectNode(),IDENTITY);
  assertThat(mapper.toMessage(mapping,unknown)).as("nothing is better than a message nobody specified").isEmpty();
 }

 @Test void whatComesBackIsReadIntoThisControllersOwnWords() throws Exception {
  var mapping=read(Files.readString(Path.of("../docs/sample-mappings/vda5050-v3.json")));
  var state=json.readTree("{\"lastNodeId\":\"AGV-M-007\",\"operatingMode\":\"AUTOMATIC\",\"driving\":true,"
   +"\"loads\":[{\"loadId\":\"CARRIER-1\"}],\"batteryState\":{\"batteryCharge\":61.5}}");
  var report=mapper.toReport(mapping,"uagv/v3/ACME/AGV-7/state",state).orElseThrow();
  assertThat(report.externalVehicleId()).isEqualTo("AGV-7");
  assertThat(report.nodeCode()).isEqualTo("AGV-M-007");
  assertThat(report.mode()).isEqualTo("REMOTE");
  assertThat(report.status()).isEqualTo("MOVING");
  assertThat(report.carrier()).isTrue();
  assertThat(report.batteryPct()).isEqualTo(61.5);
  assertThat(report.online()).as("the state message says nothing about the link").isNull();
 }

 @Test void avalueTheTableDoesNotNameIsNotPassedThrough() throws Exception {
  var mapping=read(Files.readString(Path.of("../docs/sample-mappings/vda5050-v3.json")));
  var state=json.readTree("{\"operatingMode\":\"SOMETHING_NEW\",\"driving\":false}");
  var report=mapper.toReport(mapping,"uagv/v3/ACME/AGV-7/state",state).orElseThrow();
  // A mode nobody listed is a mode nobody can act on. Saying nothing leaves the vehicle as it was;
  // guessing REMOTE would hand work to a vehicle in a state this controller has never seen.
  assertThat(report.mode()).isNull();
  assertThat(report.status()).isEqualTo("IDLE");
 }

 @Test void amessageOnATopicNoRuleDescribesIsNotARePort() throws Exception {
  var mapping=read(Files.readString(Path.of("../docs/sample-mappings/vda5050-v3.json")));
  assertThat(mapper.toReport(mapping,"uagv/v3/ACME/AGV-7/factsheet",json.createObjectNode())).isEmpty();
 }

 @Test void anEmptyLoadListMeansNoCarrier() throws Exception {
  var mapping=read(Files.readString(Path.of("../docs/sample-mappings/vda5050-v3.json")));
  var report=mapper.toReport(mapping,"uagv/v3/ACME/AGV-7/state",json.readTree("{\"loads\":[]}")).orElseThrow();
  assertThat(report.carrier()).isFalse();
 }

 @Test void adocumentSaysEverythingThatIsWrongWithItAtOnce() {
  String broken="""
   {"mapping":"","adapter":"MQTT_JSON_V1",
    "outbound":{"MOVE":{"body":{"a":{"from":"nowhere.x"}}}},
    "inbound":[{"when":{"topic":"t/{serial}"},"report":{"speed":{"from":"body.v","op":"sqrt"}}}]}
   """;
  assertThatThrownBy(()->read(broken))
   .isInstanceOfSatisfying(DeviceMessageMapping.MappingRejected.class,rejected->
    assertThat(rejected.problems()).as("all of it, so it is fixed in one pass")
     .anyMatch(p->p.contains("mapping:"))          // no name
     .anyMatch(p->p.contains("identity:"))         // no way to tell which vehicle
     .anyMatch(p->p.contains("topic이 없습니다"))    // the command has nowhere to go
     .anyMatch(p->p.contains("from은"))             // a source that does not exist
     .anyMatch(p->p.contains("op sqrt"))           // a conversion that does not exist
     .anyMatch(p->p.contains("speed")));           // a report field nobody acts on
 }

 @Test void atopicPatternCapturesWhatItNamesAndRefusesWhatItDoesNot() {
  assertThat(MessageMapper.match("{prefix}/{version}/{maker}/{serial}/state","uagv/v3/ACME/AGV-7/state"))
   .containsEntry("prefix","uagv").containsEntry("maker","ACME").containsEntry("serial","AGV-7");
  assertThat(MessageMapper.match("uagv/+/{maker}/{serial}/state","uagv/v2/ACME/AGV-7/state"))
   .containsEntry("serial","AGV-7");
  assertThat(MessageMapper.match("uagv/v3/#","uagv/v3/ACME/AGV-7/state")).isNotNull();
  assertThat(MessageMapper.match("{prefix}/v3/{maker}/{serial}/state","uagv/v2/ACME/AGV-7/state")).isNull();
  assertThat(MessageMapper.match("{prefix}/{version}/{maker}/{serial}/state","uagv/v3/ACME/state")).isNull();
 }

 /** The shipped equipment example reads, writes the one statement, and has a handshake for it. */
 @Test void theequipmentSampleSaysTheOneThingItMay() throws Exception {
  var mapping=read(Files.readString(Path.of("../docs/sample-mappings/equipment-mc-example.json")));
  assertThat(mapping.adapter()).isEqualTo("EQUIPMENT_WORD_V1");
  assertThat(mapping.outbound()).containsOnlyKeys("PORT_INTENT");
  assertThat(mapping.area().write()).isNotBlank();
  assertThat(mapping.handshake()).isNotNull();
  assertThat(mapping.handshake().echoSlot()).as("which statement was taken").isNotNull();
 }

 /**
  * An equipment document that writes something else, or has nowhere to write it, is refused.
  *
  * <p>There is one thing this controller says to a machine. A document naming another command has
  * described one that will never be sent, and a dead command in a document is worse than none --
  * somebody wrote it believing it would go.
  */
 @Test void anEquipmentDocumentMaySayOnlyTheOneThingAndMustHaveSomewhereToSayIt() {
  String wrong="""
   {"mapping":"EQ-WRONG","adapter":"EQUIPMENT_WORD_V1",
    "area":{"driver":"mc","read":"D1000","words":4,"poll-ms":500},
    "identity":{"fromSlot":"unit"},
    "layout":{"unit":{"word":0,"type":"ascii","length":4,"area":"read"},
              "cmd":{"word":2,"type":"u16"},"req":{"word":3,"bit":0},
              "took":{"word":3,"bit":1,"area":"read"},"echo":{"word":1,"type":"u16","area":"read"}},
    "inbound":[{"when":{"always":true},"report":{"status":{"from":"slot.cmd","values":{"0":"IDLE"}}}}],
    "outbound":{"OPEN_DOOR":{"slots":{"cmd":1}}},
    "handshake":{"write":["cmd"],"then-set":"req","accepted-bit":"took","echo-slot":"echo"}}
   """;
  assertThatThrownBy(()->read(wrong))
   .isInstanceOfSatisfying(DeviceMessageMapping.MappingRejected.class,rejected->
    assertThat(rejected.problems())
     .anyMatch(p->p.contains("OPEN_DOOR"))          // not a thing this controller says
     .anyMatch(p->p.contains("area.write")));       // and nowhere to say it
 }
}
