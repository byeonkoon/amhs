package com.amhs;
import com.amhs.mapio.MapDocument;
import com.amhs.mapio.MapImport;
import com.amhs.platform.ConfigurationException;
import com.amhs.topology.TopologyConfiguration;
import com.amhs.topology.TopologyQuery;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * A map arrives as one document, is checked as a whole, and becomes a revision of its own.
 *
 * <p>A hundred nodes entered through a screen one at a time is an afternoon of typing and a dozen
 * mistakes nobody can see, so a map laid out elsewhere can be brought in. Two things make that
 * safe: it is checked before anything is written, because a half-imported map still looks like a
 * map; and it never touches the one the site is running on.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class MapImportSqlTest {
 @Autowired MapImport maps; @Autowired TopologyQuery query; @Autowired JdbcTemplate jdbc;
 @Autowired TopologyConfiguration topology;
 private static final long SITE=1, IN_USE=1;
 private final List<Long> mine=new java.util.ArrayList<>();

 /** Whatever this test imported is taken out again: the suite's other maps are not its to change. */
 @AfterEach void removeWhatWasImported(){
  for(long revision:mine){
   jdbc.update("DELETE FROM topology.zone_node WHERE revision_id=?",revision);
   jdbc.update("DELETE FROM topology.zone WHERE revision_id=?",revision);
   jdbc.update("DELETE FROM fleet.vehicle_profile WHERE revision_id=?",revision);
   jdbc.update("DELETE FROM topology.port WHERE revision_id=?",revision);
   jdbc.update("DELETE FROM topology.edge WHERE revision_id=?",revision);
   jdbc.update("DELETE FROM topology.node WHERE revision_id=?",revision);
   jdbc.update("DELETE FROM topology.map_revision WHERE id=?",revision);
  }
  mine.clear();
 }
 private long imported(MapDocument document){
  long id=maps.importDocument(SITE,document).id();
  mine.add(id);
  return id;
 }
 private static MapDocument.Node node(String code,long x,long y){
  return new MapDocument.Node(code,x,y,null,null,null,null,null);
 }
 private static MapDocument.Segment segment(String from,String to){
  return new MapDocument.Segment(from,to,null,null,null);
 }
 /** A small but complete site: a loop of four, a bay, a port on it, and one vehicle. */
 private static MapDocument smallSite(){
  return new MapDocument(
   List.of(node("T-N1",0,0),node("T-N2",3000,0),node("T-N3",3000,3000),node("T-N4",0,3000),
    new MapDocument.Node("T-BAY",6000L,1500L,null,null,null,null,null),
    new MapDocument.Node("T-WAIT",-3000L,1500L,null,null,null,null,"OHT")),
   List.of(segment("T-N1","T-N2"),segment("T-N2","T-N3"),segment("T-N3","T-N4"),segment("T-N4","T-N1"),
    new MapDocument.Segment("T-N2","T-BAY",null,null,2000L),
    new MapDocument.Segment("T-BAY","T-N3",null,null,2000L),
    segment("T-N4","T-WAIT"),segment("T-WAIT","T-N1")),
   List.of(new MapDocument.Port("T-PORT","T-BAY","LOAD","OHT")),
   List.of(new MapDocument.Vehicle("T-OHT-01","test vehicle","OHT","T-N1",null,null,2000,null,null,null)),
   List.of(new MapDocument.Zone("T-ZONE",1,"MAINTENANCE",List.of("T-WAIT"))));
 }
 private void refused(Runnable call,String code){
  assertThatThrownBy(call::run).isInstanceOfSatisfying(ConfigurationException.class,
   e->assertThat(e.getMessage()).isEqualTo(code));
 }

 @Test void aMapComesInAsItsOwnDraftAndTheRunningOneIsUntouched(){
  String before=jdbc.queryForObject("SELECT status FROM topology.map_revision WHERE site_id=? AND id=?",
   String.class,SITE,IN_USE);
  int nodesBefore=query.getMap(SITE,IN_USE).nodes().size();

  long id=imported(smallSite());
  var map=query.getMap(SITE,id);
  assertThat(map.nodes()).hasSize(6);
  assertThat(map.edges()).hasSize(8);
  assertThat(map.ports()).singleElement().satisfies(p->assertThat(p.code()).isEqualTo("T-PORT"));
  assertThat(jdbc.queryForObject("SELECT status FROM topology.map_revision WHERE id=?",String.class,id))
   .as("a map is drawn before it is used").isEqualTo("DRAFT");
  assertThat(jdbc.queryForObject("SELECT status FROM topology.map_revision WHERE site_id=? AND id=?",
   String.class,SITE,IN_USE)).isEqualTo(before);
  assertThat(query.getMap(SITE,IN_USE).nodes()).hasSize(nodesBefore);

  // What the file did not say is filled in the ordinary way, not left blank.
  var bay=map.nodes().stream().filter(n->n.code().equals("T-BAY")).findFirst().orElseThrow();
  assertThat(bay.enabled()).isTrue();
  assertThat(bay.chargePoint()).isFalse();
  var wait=map.nodes().stream().filter(n->n.code().equals("T-WAIT")).findFirst().orElseThrow();
  assertThat(wait.waitFor()).isEqualTo("OHT");
  // A segment with no cost in the file costs what it actually is.
  assertThat(map.edges()).anySatisfy(e->assertThat(e.cost()).isEqualTo(3000d));
  assertThat(map.edges().stream().filter(e->e.curveMm()>0)).as("the bay junctions are curves").hasSize(2);
 }

 /** The same shape out as in, so a map can be taken, edited and brought back. */
 @Test void aMapGoesBackOutInTheShapeItCameIn(){
  long first=imported(smallSite());
  var out=maps.export(SITE,first);
  assertThat(out.nodes()).extracting(MapDocument.Node::code)
   .containsExactlyInAnyOrder("T-N1","T-N2","T-N3","T-N4","T-BAY","T-WAIT");
  assertThat(out.segments()).hasSize(8);
  assertThat(out.vehicles()).singleElement().satisfies(v->{
   assertThat(v.code()).isEqualTo("T-OHT-01");
   assertThat(v.initialNode()).isEqualTo("T-N1");
   assertThat(v.speedMmS()).isEqualTo(2000);
  });
  assertThat(out.zones()).singleElement().satisfies(z->{
   assertThat(z.kind()).isEqualTo("MAINTENANCE");
   assertThat(z.nodes()).containsExactly("T-WAIT");
  });

  // And back in again: a second revision that says the same thing, including the same vehicle code.
  long again=imported(out);
  assertThat(query.getMap(SITE,again).nodes()).hasSize(6);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM fleet.vehicle_profile WHERE revision_id=? AND code=?",
   Integer.class,again,"T-OHT-01")).as("a code names a vehicle within one map, not across the site").isEqualTo(1);
 }

 /**
  * A map brought in can be taken out again, and the one in use cannot.
  *
  * <p>Files arrive twice, so maps accumulate, and a list nobody can tidy is a list nobody reads.
  * What stops a removal is the map being in use, or anything outside the configuration still
  * remembering it -- a record naming a revision is a record of something that happened on that map,
  * and the map is the only thing that says what its node numbers meant.
  */
 @Test void anUnusedMapCanBeTakenOutAgainAndTheUsedOneCannot(){
  long id=maps.importDocument(SITE,smallSite()).id();
  String mode=jdbc.queryForObject("SELECT mode FROM platform.site_operation WHERE site_id=?",String.class,SITE);
  jdbc.update("UPDATE platform.site_operation SET mode='MAINTENANCE' WHERE site_id=?",SITE);
  try{
   topology.deleteRevision(SITE,id);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM topology.map_revision WHERE id=?",Integer.class,id)).isZero();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM topology.node WHERE revision_id=?",Integer.class,id)).isZero();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM fleet.vehicle_profile WHERE revision_id=?",Integer.class,id)).isZero();

   refused(()->topology.deleteRevision(SITE,IN_USE),"REVISION_IN_USE");
   // The sample map has orders and reported positions against it, which is the other refusal.
   long second=imported(smallSite());
   jdbc.update("INSERT INTO traffic.node_reservation(site_id,revision_id,node_id,vehicle_id,sequence)"
    +" SELECT ?,?,id,999001,9991 FROM topology.node WHERE revision_id=? ORDER BY id LIMIT 1",SITE,second,second);
   refused(()->topology.deleteRevision(SITE,second),"REVISION_HAS_HISTORY");
   jdbc.update("DELETE FROM traffic.node_reservation WHERE revision_id=?",second);
  }finally{
   jdbc.update("UPDATE platform.site_operation SET mode=? WHERE site_id=?",mode,SITE);
  }
 }

 @Test void aMapThatDoesNotHangTogetherIsRefusedBeforeAnythingIsWritten(){
  int revisions=jdbc.queryForObject("SELECT count(*) FROM topology.map_revision WHERE site_id=?",Integer.class,SITE);

  refused(()->maps.importDocument(SITE,new MapDocument(List.of(),List.of(),List.of(),List.of(),List.of())),
   "MAP_HAS_NO_TRACK");
  refused(()->maps.importDocument(SITE,new MapDocument(List.of(node("X1",0,0),node("X1",1,1)),
   List.of(),List.of(),List.of(),List.of())),"NODE_CODE_REPEATED");
  refused(()->maps.importDocument(SITE,new MapDocument(List.of(node("X1",0,0)),
   List.of(segment("X1","NOWHERE")),List.of(),List.of(),List.of())),"SEGMENT_NODE_UNKNOWN");
  refused(()->maps.importDocument(SITE,new MapDocument(List.of(node("X1",0,0)),
   List.of(),List.of(new MapDocument.Port("P","X1","LOAD","TRAIN")),List.of(),List.of())),
   "PORT_VEHICLE_TYPE_INVALID");
  refused(()->maps.importDocument(SITE,new MapDocument(List.of(node("X1",0,0)),List.of(),List.of(),
   List.of(new MapDocument.Vehicle("V","v","OHT","NOWHERE",null,null,null,null,null,null)),List.of())),
   "VEHICLE_NODE_UNKNOWN");
  // A vehicle told to charge with nowhere to charge: the setting would be a promise it cannot keep.
  refused(()->maps.importDocument(SITE,new MapDocument(List.of(node("X1",0,0)),List.of(),List.of(),
   List.of(new MapDocument.Vehicle("V","v","AGV","X1",null,null,null,"NEAREST",null,null)),List.of())),
   "CHARGING_NEEDS_A_CHARGE_NODE");
  // A charger is not a waiting place, in a file as much as on the screen.
  refused(()->maps.importDocument(SITE,new MapDocument(
   List.of(new MapDocument.Node("X1",0L,0L,null,null,null,true,"AGV")),List.of(),List.of(),List.of(),List.of())),
   "CHARGE_POINT_NOT_A_WAIT_PLACE");
  // One node in two zones would make one vehicle fill two capacities at once.
  refused(()->maps.importDocument(SITE,new MapDocument(List.of(node("X1",0,0)),List.of(),List.of(),List.of(),
   List.of(new MapDocument.Zone("Z1",1,"TRAFFIC",List.of("X1")),
    new MapDocument.Zone("Z2",1,"TRAFFIC",List.of("X1"))))),"ZONE_NODE_IN_TWO_ZONES");

  assertThat(jdbc.queryForObject("SELECT count(*) FROM topology.map_revision WHERE site_id=?",Integer.class,SITE))
   .as("nothing refused left a revision behind").isEqualTo(revisions);
 }
}
