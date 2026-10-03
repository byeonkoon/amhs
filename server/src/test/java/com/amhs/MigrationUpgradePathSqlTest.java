package com.amhs;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * Migrating an existing database, not creating a new one.
 *
 * <p>Every other test here starts from nothing, so the whole run proves only that the migrations
 * build a correct database from empty. That is the one path a site never takes. A site arrives at
 * some older version with data of its own, and the question is whether the remaining migrations
 * get it to the newest version without stopping -- because a migration that stops leaves the
 * application refusing to start, which is the worst way to find out.
 *
 * <p>The case checked here is a site that had already configured equipment under a code the sample
 * data also uses. Seeding that sample skips the row whose code is taken, and the statement after it
 * points a port at the id that was never created. It used to fail there.
 */
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class MigrationUpgradePathSqlTest {
 private static final String LOCATIONS="classpath:db/postgresql";
 /** The version a site is assumed to be sitting at: the last one before the sample equipment. */
 private static final String BEFORE_THE_SAMPLE="27";

 private static String url(){
  String configured=System.getenv("AMHS_TEST_DB_URL");
  return configured!=null?configured:"jdbc:postgresql://127.0.0.1:5432/amhs_test";
 }
 private static String user(){
  String v=System.getenv("AMHS_DB_USER"); return v!=null?v:"amhs";
 }
 private static String password(){
  String v=System.getenv("AMHS_DB_PASSWORD"); return v!=null?v:"";
 }
 /** The same cluster, a different database: this test migrates its own and leaves the others alone. */
 private static String urlOf(String database){
  String base=url();
  return base.substring(0,base.lastIndexOf('/')+1)+database+queryOf(base);
 }
 private static String queryOf(String base){
  int q=base.indexOf('?'); return q<0?"":base.substring(q);
 }

 @Test void anExistingDatabaseWithItsOwnEquipmentCodeStillReachesTheNewestVersion()throws Exception{
  String database="amhs_test_upgrade_"+UUID.randomUUID().toString().replace("-","").substring(0,10);
  createDatabase(database);
  try{
   // A site that stopped receiving migrations before the sample equipment existed.
   var atOldVersion=flyway(database,BEFORE_THE_SAMPLE);
   atOldVersion.migrate();
   assertThat(atOldVersion.info().current().getVersion().getVersion()).isEqualTo(BEFORE_THE_SAMPLE);

   // Its own equipment, under a code the sample data also wants, at an id of its own choosing.
   try(var c=connect(database)){
    c.createStatement().executeUpdate(
     "INSERT INTO equipment.unit(id,site_id,revision_id,code,name,kind,enabled)"
     +" VALUES (771001,1,1,'STK-01','현장이 먼저 등록한 스토커','STOCKER',TRUE)");
    // And a port left pointing at equipment that is not there, which must not survive either.
    c.createStatement().executeUpdate(
     "UPDATE topology.port SET equipment_unit_id=771001 WHERE site_id=1 AND revision_id=1 AND code='OHT-B'");
   }

   // The rest of the migrations. This is the step that used to roll back on a foreign key.
   flyway(database,null).migrate();

   try(var c=connect(database)){
    assertThat(appliedCount(c)).as("every migration applied").isGreaterThan(Integer.parseInt(BEFORE_THE_SAMPLE));
    assertThat(failedVersions(c)).as("no migration recorded as failed").isEmpty();
    // The site's own row is still the one it was, and nothing created a second STK-01.
    assertThat(scalar(c,"SELECT count(*) FROM equipment.unit WHERE site_id=1 AND revision_id=1 AND code='STK-01'"))
     .isEqualTo(1L);
    assertThat(scalar(c,"SELECT id FROM equipment.unit WHERE site_id=1 AND revision_id=1 AND code='STK-01'"))
     .isEqualTo(771001L);
    // The ports that meant STK-01 point at the site's row, by code, which is what they always meant.
    assertThat(scalar(c,"SELECT count(*) FROM topology.port WHERE site_id=1 AND revision_id=1"
     +" AND code IN ('OHT-A','OHT-D') AND equipment_unit_id=771001")).isEqualTo(2L);
    // No port is left pointing at equipment that does not exist.
    assertThat(scalar(c,"SELECT count(*) FROM topology.port p WHERE p.equipment_unit_id IS NOT NULL"
     +" AND NOT EXISTS (SELECT 1 FROM equipment.unit u WHERE u.id=p.equipment_unit_id)")).isEqualTo(0L);
   }
  } finally { dropDatabase(database); }
 }

 /** Migrating twice changes nothing the second time: the callback has to be safe to repeat. */
 @Test void migratingAnAlreadyCurrentDatabaseIsQuietAndChangesNoLinks()throws Exception{
  String database="amhs_test_upgrade_"+UUID.randomUUID().toString().replace("-","").substring(0,10);
  createDatabase(database);
  try{
   flyway(database,null).migrate();
   String before;
   try(var c=connect(database)){ before=portLinks(c); }
   flyway(database,null).migrate();
   try(var c=connect(database)){
    assertThat(portLinks(c)).isEqualTo(before);
    assertThat(failedVersions(c)).isEmpty();
   }
  } finally { dropDatabase(database); }
 }

 private static Flyway flyway(String database,String target){
  var configured=Flyway.configure()
   .dataSource(urlOf(database),user(),password())
   .locations(LOCATIONS)
   .defaultSchema("public");
  if(target!=null) configured=configured.target(MigrationVersion.fromVersion(target));
  return configured.load();
 }
 private static Connection connect(String database)throws SQLException{
  return DriverManager.getConnection(urlOf(database),user(),password());
 }
 /** CREATE and DROP DATABASE cannot run inside a transaction, so they go to the maintenance database. */
 private static void createDatabase(String database)throws SQLException{
  try(var c=DriverManager.getConnection(urlOf("postgres"),user(),password());var s=c.createStatement()){
   s.executeUpdate("CREATE DATABASE \""+database+"\" ENCODING 'UTF8'");
  }
 }
 private static void dropDatabase(String database){
  try(var c=DriverManager.getConnection(urlOf("postgres"),user(),password());var s=c.createStatement()){
   s.executeUpdate("DROP DATABASE IF EXISTS \""+database+"\" WITH (FORCE)");
  }catch(SQLException ignored){ /* the cluster is the test cluster; a leftover is visible there */ }
 }
 private static long scalar(Connection c,String sql)throws SQLException{
  try(var s=c.createStatement();var r=s.executeQuery(sql)){ r.next(); return r.getLong(1); }
 }
 private static int appliedCount(Connection c)throws SQLException{
  return (int)scalar(c,"SELECT count(*) FROM public.flyway_schema_history WHERE success=TRUE AND version IS NOT NULL");
 }
 private static String failedVersions(Connection c)throws SQLException{
  var out=new StringBuilder();
  try(var s=c.createStatement();
      var r=s.executeQuery("SELECT version FROM public.flyway_schema_history WHERE success=FALSE ORDER BY installed_rank")){
   while(r.next()) out.append(r.getString(1)).append(' ');
  }
  return out.toString().trim();
 }
 private static String portLinks(Connection c)throws SQLException{
  var out=new StringBuilder();
  try(var s=c.createStatement();
      var r=s.executeQuery("SELECT code,coalesce(equipment_unit_id,-1) FROM topology.port ORDER BY code")){
   while(r.next()) out.append(r.getString(1)).append('=').append(r.getLong(2)).append(' ');
  }
  return out.toString().trim();
 }
}
