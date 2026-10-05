package com.amhs;
import com.amhs.platform.ConfigurationException;
import com.amhs.topology.TopologyConfiguration;
import com.amhs.transport.OrderRequest;
import com.amhs.transport.TransportOrders;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * Which map the site runs on can be changed, and not while anything is standing on the old one.
 *
 * <p>A revision is the ground every order, every granted road and every reported position is
 * written against. Swapping it under open work would leave those records naming nodes that are not
 * there any more -- and unlike a wrong parameter, that cannot be put right by editing it back.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class MapRevisionSqlTest {
 @Autowired TopologyConfiguration topology; @Autowired TransportOrders orders;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, ACTIVE=1, DRAFT=2;

 /**
  * The mode this test found the site in. Other tests expect the one they left behind, so it is put
  * back rather than set to whatever looks right.
  */
 private String modeBefore;

 @BeforeEach void quietSiteInMaintenance(){
  modeBefore=jdbc.queryForObject("SELECT mode FROM platform.site_operation WHERE site_id=?",String.class,SITE);
  settleEverything();
  jdbc.update("UPDATE platform.site_operation SET mode='MAINTENANCE' WHERE site_id=?",SITE);
 }
 /** Back to the map and the mode the rest of the suite is written against. */
 @AfterEach void leaveItAsItWas(){
  settleEverything();
  if(modeBefore!=null) jdbc.update("UPDATE platform.site_operation SET mode=? WHERE site_id=?",modeBefore,SITE);
 }
 private void settleEverything(){
  jdbc.update("UPDATE transport.order SET status='CANCELLED',terminal_at=now() WHERE site_id=? AND status NOT IN ('COMPLETED','CANCELLED','FAILED')",SITE);
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE site_id=? AND finished_at IS NULL",SITE);
  jdbc.update("UPDATE traffic.node_reservation SET released_at=now(),release_reason='TEST' WHERE site_id=? AND released_at IS NULL",SITE);
  jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=?",SITE);
  jdbc.update("UPDATE topology.map_revision SET status='RETIRED' WHERE site_id=? AND status='ACTIVE' AND id<>?",SITE,ACTIVE);
  jdbc.update("UPDATE topology.map_revision SET status='DRAFT' WHERE site_id=? AND id<>? AND status<>'ACTIVE'",SITE,ACTIVE);
  jdbc.update("UPDATE topology.map_revision SET status='ACTIVE' WHERE site_id=? AND id=?",SITE,ACTIVE);
 }
 private String statusOf(long revisionId){
  return topology.revisions(SITE).stream().filter(r->r.id()==revisionId).findFirst().orElseThrow().status();
 }
 private void refused(Runnable call,int status,String code){
  assertThatThrownBy(call::run).isInstanceOfSatisfying(ConfigurationException.class,e->{
   assertThat(e.status()).isEqualTo(status); assertThat(e.getMessage()).isEqualTo(code);
  });
 }

 @Test void theRevisionsSayWhatTheyHoldAndWhichOneIsInUse(){
  var all=topology.revisions(SITE);
  assertThat(all).extracting(TopologyConfiguration.RevisionView::id).contains(ACTIVE,DRAFT);
  var active=all.stream().filter(TopologyConfiguration.RevisionView::active).toList();
  assertThat(active).as("exactly one map is in use").hasSize(1);
  assertThat(active.getFirst().id()).isEqualTo(ACTIVE);
  assertThat(active.getFirst().nodes()).isPositive();
  assertThat(active.getFirst().vehicles()).isPositive();
 }

 @Test void aRevisionIsPutIntoUseAndTheOldOneIsKept(){
  var now=topology.activate(SITE,DRAFT);
  assertThat(now.active()).isTrue();
  assertThat(statusOf(ACTIVE)).as("kept, because the finished work was done on it").isEqualTo("RETIRED");
  // Asking again changes nothing and is not an error.
  assertThat(topology.activate(SITE,DRAFT).id()).isEqualTo(DRAFT);
 }

 @Test void anOpenOrderStopsTheMapChanging(){
  jdbc.update("UPDATE platform.site_operation SET mode='RUNNING' WHERE site_id=?",SITE);
  long id=tx.execute(s->orders.accept(SITE,new OrderRequest("MAP",UUID.randomUUID().toString(),"OHT-A","OHT-C",
   "MAP-"+UUID.randomUUID(),50),"test",null).order().id());
  jdbc.update("UPDATE platform.site_operation SET mode='MAINTENANCE' WHERE site_id=?",SITE);

  refused(()->topology.activate(SITE,DRAFT),409,"ORDERS_STILL_OPEN");

  jdbc.update("UPDATE transport.order SET status='CANCELLED',terminal_at=now() WHERE id=?",id);
  assertThat(topology.activate(SITE,DRAFT).active()).isTrue();
 }

 /**
  * A vehicle still driving stops the map changing. A vehicle standing still does not.
  *
  * <p>A standing vehicle holds the node it stands on, permanently -- that hold is what stops anyone
  * else being sent into it. Counting it as traffic in progress made the map impossible to change on
  * a site where nothing was happening at all, which is the opposite of careful.
  */
 @Test void aVehicleStillDrivingStopsTheMapChangingAndAStandingOneDoesNot(){
  long standing=999004;   // a vehicle of this test's own, so no other test's state is touched
  try{
   // Standing on the node it holds, with a position reported: the ordinary quiet site.
   jdbc.update("INSERT INTO fleet.vehicle_state(site_id,vehicle_id,revision_id,node_id,online,remote,idle,"
    +"carrier_present,source,reported_at) VALUES(?,?,?,?,TRUE,TRUE,TRUE,FALSE,'SIMULATED',now())",
    SITE,standing,ACTIVE,101);
   jdbc.update("INSERT INTO traffic.node_reservation(site_id,revision_id,node_id,vehicle_id,sequence)"
    +" VALUES(?,?,?,?,?)",SITE,ACTIVE,101,standing,1);
   assertThat(topology.activate(SITE,DRAFT).active()).as("nothing is moving").isTrue();
   settleEverything();

   // Road still granted ahead of a vehicle that has stopped: it is standing, and standing still is
   // not something a map change disturbs. The run it had is over; nothing will drive it until
   // somebody starts the site again.
   jdbc.update("INSERT INTO traffic.node_reservation(site_id,revision_id,node_id,vehicle_id,sequence)"
    +" VALUES(?,?,?,?,?)",SITE,ACTIVE,102,standing,2);
   assertThat(topology.activate(SITE,DRAFT).active()).as("it has stopped, wherever its road ended").isTrue();
   settleEverything();

   // Between two nodes: moving, and a map changed under it leaves it on track that is gone.
   jdbc.update("UPDATE fleet.vehicle_state SET x_mm=1,y_mm=1 WHERE site_id=? AND vehicle_id=?",SITE,standing);
   jdbc.update("INSERT INTO traffic.node_reservation(site_id,revision_id,node_id,vehicle_id,sequence)"
    +" VALUES(?,?,?,?,?)",SITE,ACTIVE,101,standing,3);
   refused(()->topology.activate(SITE,DRAFT),409,"ROADS_STILL_GRANTED");
   jdbc.update("UPDATE fleet.vehicle_state SET x_mm=NULL,y_mm=NULL WHERE site_id=? AND vehicle_id=?",SITE,standing);

   // Its last word is old: where it is now is a guess, and a guess is not a vehicle standing still.
   jdbc.update("UPDATE fleet.vehicle_state SET reported_at=now()-interval '1 hour'"
    +" WHERE site_id=? AND vehicle_id=?",SITE,standing);
   refused(()->topology.activate(SITE,DRAFT),409,"ROADS_STILL_GRANTED");

   // A hold whose vehicle has not said where it is: not known to be anywhere, so not known to be still.
   jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=? AND vehicle_id=?",SITE,standing);
   refused(()->topology.activate(SITE,DRAFT),409,"ROADS_STILL_GRANTED");
  }finally{
   jdbc.update("DELETE FROM traffic.node_reservation WHERE site_id=? AND vehicle_id=?",SITE,standing);
   jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=? AND vehicle_id=?",SITE,standing);
  }
 }

 /** A hold on a map the site has stopped running is not permission to be anywhere. */
 @Test void holdsLeftOnARetiredMapDoNotStopTheNextChange(){
  jdbc.update("INSERT INTO traffic.node_reservation(site_id,revision_id,node_id,vehicle_id,sequence)"
   +" VALUES(?,?,?,?,?)",SITE,DRAFT,1101,999003,9993);
  assertThat(topology.activate(SITE,DRAFT).active()).as("the hold is not on the map in use").isTrue();
 }

 /** A map with no track, or no vehicle, is a site that can do nothing. One click is enough. */
 @Test void anEmptyRevisionIsNotPutIntoUse(){
  long empty=jdbc.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM topology.map_revision",Long.class);
  int number=jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM topology.map_revision WHERE site_id=?",Integer.class,SITE);
  jdbc.update("INSERT INTO topology.map_revision(id,site_id,revision,status) VALUES(?,?,?,'DRAFT')",empty,SITE,number);
  try{
   refused(()->topology.activate(SITE,empty),422,"REVISION_HAS_NO_TRACK");
   jdbc.update("INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled) VALUES(?,?,?,?,0,0,TRUE)",
    900001,SITE,empty,"EMPTY-N1");
   refused(()->topology.activate(SITE,empty),422,"REVISION_HAS_NO_VEHICLE");
  }finally{
   jdbc.update("DELETE FROM topology.node WHERE revision_id=?",empty);
   jdbc.update("DELETE FROM topology.map_revision WHERE id=?",empty);
  }
 }

 @Test void theSiteMustBeInMaintenanceToChangeItsMap(){
  jdbc.update("UPDATE platform.site_operation SET mode='RUNNING' WHERE site_id=?",SITE);
  refused(()->topology.activate(SITE,DRAFT),409,"SITE_NOT_IN_MAINTENANCE");
  assertThat(statusOf(ACTIVE)).isEqualTo("ACTIVE");
 }

 @Test void anUnknownRevisionIsNotFound(){
  refused(()->topology.activate(SITE,987654),404,"REVISION_NOT_FOUND");
 }
}
