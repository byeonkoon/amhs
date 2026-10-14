package com.amhs;
import com.amhs.mapio.MapDocument;
import com.amhs.mapio.MapImport;
import com.amhs.platform.ConfigurationException;
import com.amhs.topology.TopologyConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * Review L8 of b2b48d4, as the behaviour wanted: a map whose OHT and AGV track are joined -- an AGV
 * port between two OHT nodes on one piece of track -- is refused when somebody tries to put it in
 * use. The maps the site ships with keep the two kinds apart and pass.
 */
@SpringBootTest(properties={"spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class MixedNetworkSqlTest {
 @Autowired MapImport maps; @Autowired TopologyConfiguration topology; @Autowired JdbcTemplate jdbc;

 @Test @Transactional void aMapJoiningOhtAndAgvTrackCannotBePutInUse(){
  jdbc.update("UPDATE platform.site_operation SET mode='MAINTENANCE' WHERE site_id=1");
  jdbc.update("UPDATE transport.order SET status='CANCELLED' WHERE site_id=1");
  jdbc.update("UPDATE transport.relocation SET finished_at=now() WHERE site_id=1 AND finished_at IS NULL");
  jdbc.update("UPDATE traffic.node_reservation SET released_at=now() WHERE site_id=1 AND released_at IS NULL");
  var doc=new MapDocument(
   List.of(new MapDocument.Node("MX-S",0L,0L,true,false,false,false,null),
    new MapDocument.Node("MX-M",1000L,0L,true,false,false,false,null),
    new MapDocument.Node("MX-D",2000L,0L,true,false,false,false,null)),
   List.of(new MapDocument.Segment("MX-S","MX-M",1000L,true,0L),new MapDocument.Segment("MX-M","MX-D",1000L,true,0L)),
   List.of(new MapDocument.Port("MX-S-P","MX-S","LOAD","OHT",null),
    new MapDocument.Port("MX-M-P","MX-M","LOAD","AGV",null),new MapDocument.Port("MX-D-P","MX-D","LOAD","OHT",null)),
   List.of(new MapDocument.Vehicle("MX-OHT","mixed","OHT","MX-S",null,null,1000,"DISABLED",true,true)),List.of());
  long id=maps.importDocument(1,doc).id();
  assertThat(topology.mixedNetwork(1,id)).isNotNull();
  assertThatThrownBy(()->topology.activate(1,id))
   .isInstanceOfSatisfying(ConfigurationException.class,e->assertThat(e.getMessage()).isEqualTo("MIXED_VEHICLE_NETWORK"));
  assertThat(topology.revisions(1).stream().filter(r->r.id()==id).findFirst().orElseThrow().active()).isFalse();
 }

 @Test void theShippedMapsKeepTheKindsApart(){
  // The seeded maps: the test fixture (1), its draft (2) and the large layout (3).
  for(var r:topology.revisions(1)) if(r.revision()<=3)
   assertThat(topology.mixedNetwork(1,r.id())).as("revision %d",r.revision()).isNull();
 }
}
