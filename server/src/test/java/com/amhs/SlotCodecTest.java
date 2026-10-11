package com.amhs;
import com.amhs.integration.mapping.SlotCodec;
import com.amhs.integration.mapping.VehicleMessageMapping;
import com.amhs.integration.mapping.VehicleMessageMapping.Slot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Values in and out of bytes, which is all a PC frame or a PLC register block is.
 *
 * <p>The cases that matter are the refusals. A node number that does not fit the slot it is being
 * written into is a different node once it is truncated, and a frame one byte short is not nearly
 * right -- so both are refused here rather than sent.
 */
class SlotCodecTest {
 private final ObjectMapper json=new ObjectMapper();
 private final SlotCodec codec=new SlotCodec(json);

 private static Slot slot(String name,int at,String type){ return new Slot(name,at,type,null,0,1,1.0,"write"); }
 private static Slot slot(String name,int at,String type,int length,int repeat,double scale){
  return new Slot(name,at,type,null,length,repeat,scale,"write");
 }
 private static Slot bit(String name,int at,int bit){ return new Slot(name,at,"bit",bit,0,1,1.0,"write"); }

 @Test void wholeNumbersGoOutAndComeBackTheSame(){
  var frame=new byte[16];
  codec.write(frame,slot("a",0,"u16"),json.getNodeFactory().numberNode(515),false);
  codec.write(frame,slot("b",2,"u8"),json.getNodeFactory().numberNode(7),false);
  codec.write(frame,slot("c",4,"i16"),json.getNodeFactory().numberNode(-3),false);
  assertThat(frame[0]).isEqualTo((byte)0x02);
  assertThat(frame[1]).isEqualTo((byte)0x03);
  assertThat(codec.read(frame,slot("a",0,"u16"),false).asInt()).isEqualTo(515);
  assertThat(codec.read(frame,slot("b",2,"u8"),false).asInt()).isEqualTo(7);
  assertThat(codec.read(frame,slot("c",4,"i16"),false).asInt()).isEqualTo(-3);
 }

 @Test void theEndianTheDocumentAsksForIsTheOneUsed(){
  var frame=new byte[4];
  codec.write(frame,slot("a",0,"u16"),json.getNodeFactory().numberNode(0x0102),true);
  assertThat(frame[0]).isEqualTo((byte)0x02);
  assertThat(frame[1]).isEqualTo((byte)0x01);
  assertThat(codec.read(frame,slot("a",0,"u16"),true).asInt()).isEqualTo(0x0102);
  assertThat(codec.read(frame,slot("a",0,"u16"),false).asInt())
   .as("read the other way round, it is another number").isEqualTo(0x0201);
 }

 @Test void ascaledValueIsTheNumberItStandsFor(){
  var frame=new byte[4];
  codec.write(frame,slot("battery",0,"u16",0,1,0.1),json.getNodeFactory().numberNode(61.5),false);
  assertThat(codec.read(frame,slot("battery",0,"u16",0,1,0.1),false).asDouble()).isEqualTo(61.5);
  assertThat(codec.read(frame,slot("raw",0,"u16"),false).asInt()).as("on the wire it is an integer").isEqualTo(615);
 }

 @Test void arunOfTheSameTypeIsAList(){
  var frame=new byte[16];
  var nodes=json.createArrayNode().add(203).add(204).add(207);
  codec.write(frame,slot("nodes",0,"u16",0,6,1.0),nodes,false);
  var read=codec.read(frame,slot("nodes",0,"u16",0,6,1.0),false);
  assertThat(read.size()).isEqualTo(6);
  assertThat(read.get(0).asInt()).isEqualTo(203);
  assertThat(read.get(2).asInt()).isEqualTo(207);
  assertThat(read.get(3).asInt()).as("the places nobody filled read as zero, which is what is on the wire").isZero();
 }

 @Test void textIsPaddedAndTrimmedBackAgain(){
  var frame=new byte[16];
  codec.write(frame,slot("vehicle",0,"ascii",8,1,1.0),json.getNodeFactory().textNode("AGV-7"),false);
  assertThat(frame[5]).as("padded with spaces").isEqualTo((byte)' ');
  assertThat(codec.read(frame,slot("vehicle",0,"ascii",8,1,1.0),false).asText()).isEqualTo("AGV-7");
 }

 @Test void abitIsOneBitAndLeavesTheRestAlone(){
  var frame=new byte[]{(byte)0b1010_0000,0,0,0};
  codec.write(frame,bit("request",0,0),json.getNodeFactory().booleanNode(true),false);
  assertThat(frame[0]).isEqualTo((byte)0b1010_0001);
  assertThat(codec.read(frame,bit("request",0,0),false).asBoolean()).isTrue();
  assertThat(codec.read(frame,bit("other",0,1),false).asBoolean()).isFalse();
  codec.write(frame,bit("request",0,0),json.getNodeFactory().booleanNode(false),false);
  assertThat(frame[0]).as("only that bit changed").isEqualTo((byte)0b1010_0000);
 }

 @Test void bcdIsReadAsTheDigitsItShows(){
  var frame=new byte[]{0x12,0x34,0,0};
  assertThat(codec.read(frame,slot("count",0,"bcd",2,1,1.0),false).asInt()).isEqualTo(1234);
  var out=new byte[4];
  codec.write(out,slot("count",0,"bcd",2,1,1.0),json.getNodeFactory().numberNode(5678),false);
  assertThat(out[0]).isEqualTo((byte)0x56);
  assertThat(out[1]).isEqualTo((byte)0x78);
 }

 @Test void avalueThatDoesNotFitIsRefusedRatherThanTruncated(){
  var frame=new byte[8];
  assertThatThrownBy(()->codec.write(frame,slot("a",0,"u8"),json.getNodeFactory().numberNode(300),false))
   .isInstanceOf(SlotCodec.SlotRejected.class).hasMessageContaining("u8");
  assertThatThrownBy(()->codec.write(frame,slot("v",0,"ascii",4,1,1.0),json.getNodeFactory().textNode("AGV-1234"),false))
   .isInstanceOf(SlotCodec.SlotRejected.class).hasMessageContaining("4바이트");
  assertThatThrownBy(()->codec.write(frame,slot("a",7,"u16"),json.getNodeFactory().numberNode(1),false))
   .isInstanceOf(SlotCodec.SlotRejected.class).hasMessageContaining("밖");
  var nodes=json.createArrayNode().add(1).add(2).add(3);
  assertThatThrownBy(()->codec.write(frame,slot("nodes",0,"u16",0,2,1.0),nodes,false))
   .isInstanceOf(SlotCodec.SlotRejected.class).hasMessageContaining("자리는 2개");
 }

 @Test void acheckSumCoversWhatTheDocumentSaysItCovers(){
  var frame=new byte[]{0x02,0x10,0x00,0x05,0x00,0x00};
  int xor=codec.checksum(frame,new VehicleMessageMapping.Checksum("xor",4,1,3));
  assertThat(xor).isEqualTo(0x10^0x00^0x05);
  int sum=codec.checksum(frame,new VehicleMessageMapping.Checksum("sum",4,1,3));
  assertThat(sum).isEqualTo(0x10+0x00+0x05);
 }

 @Test void thepositionalSamplesShippedWithTheRepositoryAreValid() throws Exception {
  for(String file:new String[]{"pc-frame-example.json","plc-word-example.json"}){
   var mapping=VehicleMessageMapping.read(json,Files.readString(Path.of("../docs/sample-mappings/"+file)));
   assertThat(mapping.layout()).as(file).isNotEmpty();
   assertThat(mapping.outbound()).as(file).containsKeys("MOVE","STOP");
  }
 }

 @Test void adocumentIsJudgedAgainstTheAdapterItNames(){
  // A register mapping without a handshake: the values would be written and nobody could say
  // whether the controller took them, or is about to take them a second time.
  String noHandshake="""
   {"mapping":"X","adapter":"PLC_WORD_V1","identity":{"fromSlot":"sequence"},
    "area":{"write":"40100","read":"40200","words":8,"poll-ms":300},
    "layout":{"sequence":{"word":0,"type":"u16"}},
    "outbound":{"MOVE":{"slots":{"sequence":{"from":"command.sequence"}}}},
    "inbound":[{"when":{"always":true},"report":{"nodeId":{"from":"slot.sequence"}}}]}
   """;
  assertThatThrownBy(()->VehicleMessageMapping.read(json,noHandshake))
   .isInstanceOfSatisfying(VehicleMessageMapping.MappingRejected.class,r->
    assertThat(r.problems()).anyMatch(p->p.contains("handshake")&&p.contains("레지스터는 값이 남습니다")));

  // A block that belongs to another adapter is refused rather than ignored: somebody believing
  // their register mapping addresses vehicles by topic finds out here and not on a floor.
  String topicInPlc="""
   {"mapping":"X","adapter":"PC_FRAME_V1","identity":{"fromTopic":"serial"},
    "frame":{"size":8},"layout":{"a":{"at":0,"type":"u8"}},
    "outbound":{"MOVE":{"topic":"t/1","body":{"x":1}}},
    "inbound":[{"when":{"topic":"t/1"},"report":{"nodeId":{"from":"body.x"}}}]}
   """;
  assertThatThrownBy(()->VehicleMessageMapping.read(json,topicInPlc))
   .isInstanceOfSatisfying(VehicleMessageMapping.MappingRejected.class,r->
    assertThat(r.problems())
     .anyMatch(p->p.contains("identity.fromTopic"))
     .anyMatch(p->p.contains("outbound.MOVE.topic"))
     .anyMatch(p->p.contains("outbound.MOVE: slots가 없습니다"))
     .anyMatch(p->p.contains("inbound[0].when.topic")));

  // And a named mapping must not carry positions.
  String layoutInJson="""
   {"mapping":"X","adapter":"MQTT_JSON_V1","identity":{"fromTopic":"serial"},
    "layout":{"a":{"at":0,"type":"u8"}},
    "outbound":{"MOVE":{"topic":"t/{serial}","body":{"x":1}}},
    "inbound":[{"when":{"topic":"t/{serial}"},"report":{"nodeId":{"from":"body.x"}}}]}
   """;
  assertThatThrownBy(()->VehicleMessageMapping.read(json,layoutInJson))
   .isInstanceOfSatisfying(VehicleMessageMapping.MappingRejected.class,r->
    assertThat(r.problems()).anyMatch(p->p.contains("layout:")&&p.contains("자리를 적지 않습니다")));
 }
}
