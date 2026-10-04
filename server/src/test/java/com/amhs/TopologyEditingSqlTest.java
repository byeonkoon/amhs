package com.amhs;
import com.amhs.fleet.VehicleConfiguration;
import com.amhs.fleet.VehicleSettings;
import com.amhs.platform.ConfigurationException;
import com.amhs.platform.VehicleType;
import com.amhs.topology.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * Adding and removing track, ports and vehicles while the plant exists.
 *
 * <p>Changing a parameter is undone by changing it back. Removing something is not: whatever named
 * it is left naming nothing. So a removal says what still depends on the thing, by name, and
 * refuses -- and it refuses outright while any work is open, because open work names nodes and
 * ports in records that are not configuration at all.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class TopologyEditingSqlTest {
 @Autowired TopologyConfiguration topology; @Autowired PortConfiguration ports;
 @Autowired VehicleConfiguration vehicles; @Autowired JdbcTemplate jdbc;
 private static final long SITE=1, REVISION=1;
 private String modeBefore;

 @BeforeEach void maintenanceAndQuiet(){
  modeBefore=jdbc.queryForObject("SELECT mode FROM platform.site_operation WHERE site_id=?",String.class,SITE);
  jdbc.update("UPDATE transport.order SET status='CANCELLED',terminal_at=now() WHERE site_id=? AND status NOT IN ('COMPLETED','CANCELLED','FAILED')",SITE);
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE site_id=? AND finished_at IS NULL",SITE);
  jdbc.update("UPDATE traffic.node_reservation SET released_at=now(),release_reason='TEST' WHERE site_id=? AND released_at IS NULL",SITE);
  jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=?",SITE);
  jdbc.update("UPDATE platform.site_operation SET mode='MAINTENANCE' WHERE site_id=?",SITE);
 }
 @AfterEach void leaveItAsItWas(){
  jdbc.update("DELETE FROM topology.edge WHERE site_id=? AND revision_id=? AND id>=100000",SITE,REVISION);
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE vehicle_id IN"
   +" (SELECT id FROM fleet.vehicle_profile WHERE site_id=? AND code LIKE 'EDIT-%')",SITE);
  jdbc.update("DELETE FROM fleet.vehicle_profile WHERE site_id=? AND code LIKE 'EDIT-%'",SITE);
  jdbc.update("DELETE FROM topology.port WHERE site_id=? AND code LIKE 'EDIT-%'",SITE);
  jdbc.update("DELETE FROM topology.edge WHERE site_id=? AND (from_node_id IN"
   +" (SELECT id FROM topology.node WHERE site_id=? AND code LIKE 'EDIT-%')"
   +" OR to_node_id IN (SELECT id FROM topology.node WHERE site_id=? AND code LIKE 'EDIT-%'))",SITE,SITE,SITE);
  jdbc.update("DELETE FROM topology.node WHERE site_id=? AND code LIKE 'EDIT-%'",SITE);
  if(modeBefore!=null) jdbc.update("UPDATE platform.site_operation SET mode=? WHERE site_id=?",modeBefore,SITE);
 }
 private NodeSettings node(String code,long x,long y){
  return new NodeSettings(code,x,y,true,false,false,false,null);
 }
 private void refused(Runnable call,int status,String code){
  assertThatThrownBy(call::run).isInstanceOfSatisfying(ConfigurationException.class,e->{
   assertThat(e.status()).isEqualTo(status); assertThat(e.getMessage()).isEqualTo(code);
  });
 }

 @Test void nodesAndSegmentsAreAddedAndTakenAwayAgain(){
  var first=topology.createNode(SITE,REVISION,node("EDIT-N1",900_000,900_000));
  var second=topology.createNode(SITE,REVISION,node("EDIT-N2",903_000,900_000));
  assertThat(topology.nodes(SITE,REVISION)).extracting(r->r.settings().code()).contains("EDIT-N1","EDIT-N2");
  refused(()->topology.createNode(SITE,REVISION,node("EDIT-N1",0,0)),409,"NODE_CODE_IN_USE");

  var link=topology.createEdge(SITE,REVISION,new EdgeSettings(first.id(),second.id(),3000,true,0.0));
  refused(()->topology.createEdge(SITE,REVISION,new EdgeSettings(first.id(),second.id(),3000,true,0.0)),
   409,"SEGMENT_ALREADY_EXISTS");
  refused(()->topology.createEdge(SITE,REVISION,new EdgeSettings(first.id(),first.id(),3000,true,0.0)),
   422,"SEGMENT_ENDS_AT_ITS_START");

  // A node a segment reaches is not taken away quietly: the segment is named and the node stays.
  refused(()->topology.deleteNode(SITE,REVISION,first.id()),409,"NODE_HAS_SEGMENTS");
  topology.deleteEdge(SITE,REVISION,link.id());
  topology.deleteNode(SITE,REVISION,first.id());
  topology.deleteNode(SITE,REVISION,second.id());
  assertThat(topology.nodes(SITE,REVISION)).extracting(r->r.settings().code()).doesNotContain("EDIT-N1","EDIT-N2");
 }

 @Test void aNodeSomethingElseNamesIsKept(){
  var stand=topology.createNode(SITE,REVISION,node("EDIT-N3",910_000,910_000));
  // A port on it.
  var port=ports.create(SITE,new PortSettings(REVISION,"EDIT-P1","LOAD",VehicleType.OHT,stand.id(),null));
  refused(()->topology.deleteNode(SITE,REVISION,stand.id()),409,"NODE_HAS_PORT");
  ports.delete(SITE,port.id());

  // A vehicle that starts on it.
  var made=vehicles.create(SITE,new VehicleSettings(REVISION,"EDIT-V1","edit",VehicleType.OHT,1000,1000,1000,
   stand.id(),null,null,false,false,"CONTROLLER_MANAGED","DISABLED","DISABLED",null,2000));
  refused(()->topology.deleteNode(SITE,REVISION,stand.id()),409,"NODE_IN_VEHICLE_SETTINGS");
  vehicles.delete(SITE,made.id());

  topology.deleteNode(SITE,REVISION,stand.id());
  assertThat(topology.nodes(SITE,REVISION)).extracting(r->r.settings().code()).doesNotContain("EDIT-N3");
 }

 /** Nothing is removed from under work that is still going on. */
 @Test void whileWorkIsOpenNothingIsRemoved(){
  var stand=topology.createNode(SITE,REVISION,node("EDIT-N4",920_000,920_000));
  jdbc.update("INSERT INTO traffic.node_reservation(site_id,revision_id,node_id,vehicle_id,sequence)"
   +" VALUES(?,?,?,?,?)",SITE,REVISION,101,1,1);
  try{
   refused(()->topology.deleteNode(SITE,REVISION,stand.id()),409,"ROADS_STILL_GRANTED");
  }finally{
   jdbc.update("UPDATE traffic.node_reservation SET released_at=now(),release_reason='TEST'"
    +" WHERE site_id=? AND released_at IS NULL",SITE);
  }
  topology.deleteNode(SITE,REVISION,stand.id());
 }

 @Test void addingToAMapThatIsNotThereIsNotFound(){
  refused(()->topology.createNode(SITE,987654,node("EDIT-N5",0,0)),404,"REVISION_NOT_FOUND");
 }

 /** Removing is a configuration change like any other: the site has to be in maintenance. */
 @Test void removalStillNeedsMaintenance(){
  var stand=topology.createNode(SITE,REVISION,node("EDIT-N6",930_000,930_000));
  jdbc.update("UPDATE platform.site_operation SET mode='RUNNING' WHERE site_id=?",SITE);
  refused(()->topology.deleteNode(SITE,REVISION,stand.id()),409,"SITE_NOT_IN_MAINTENANCE");
  jdbc.update("UPDATE platform.site_operation SET mode='MAINTENANCE' WHERE site_id=?",SITE);
  topology.deleteNode(SITE,REVISION,stand.id());
 }
}
