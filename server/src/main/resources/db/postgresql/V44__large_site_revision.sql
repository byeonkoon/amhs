-- A site big enough to see the control problems in: a one-way OHT main line with work bays and an
-- inspection bay off it, a separate AGV loop with its own work bays, chargers and waiting places,
-- and a fleet of 15 OHTs and 10 AGVs.
--
-- It goes in as a revision of its own and is left DRAFT. The existing sample map stays the active
-- one: it is what the server's tests are written against, node by node, and a sample is not worth
-- rewriting several hundred checks for. An administrator puts this one into use when they want it.
--
-- Shape, and why:
--  * The main line is a one-way ring. A vehicle that cannot pass another is the whole of traffic
--    control; a two-way line would hide it.
--  * Work positions hang off the ring on a spur that leaves it and rejoins it. A dead end on a
--    one-way line can never be left, so a bay that is a dead end is a vehicle lost for good.
--  * Bay entries and exits are curved. A rail vehicle does not spin on the spot; a right-angled
--    junction drawn on the screen is a corner no vehicle could take, and the screen is where
--    somebody decides whether a layout is possible.
--  * Charging places and waiting places are separate nodes. A vehicle parked on a charger is a
--    charger nobody else can use, which is why the two are never the same node.
--  * The inspection bay is a maintenance zone: vehicles sent there are meant to stay, so the
--    rule that empties an ordinary zone must not reach into it.
--
-- Vehicle codes continue the site's numbering (OHT-11.., AGV-11..) rather than repeating OHT-01.
-- A code identifies a vehicle across the whole site, not within one map, so two revisions cannot
-- both name an OHT-01.
DO $$
DECLARE
 rev CONSTANT BIGINT := 3;
 site CONSTANT BIGINT := 1;
 nid BIGINT := 3000;          -- node ids for this revision live in 3000+
 eid BIGINT := 3000;
 pid BIGINT := 3000;
 vid BIGINT := 3000;
 zid CONSTANT BIGINT := 3000;
 ring BIGINT[] := '{}';       -- the main line, in driving order
 agv BIGINT[] := '{}';
 chg BIGINT[] := '{}';          -- the AGV charge points, so each AGV is given one to go to
 prev BIGINT; entry BIGINT; mid BIGINT; leave BIGINT; stem BIGINT;
 i INT; b INT; at INT;
 step CONSTANT INT := 7000;   -- node spacing, mm
 curve CONSTANT INT := 2200;  -- how far a bay connection bows off the straight line, mm
BEGIN
 INSERT INTO topology.map_revision(id,site_id,revision,status) VALUES(rev,site,3,'DRAFT');

 -- ---- OHT main line: one way clockwise around a rectangle ----
 FOR i IN 0..19 LOOP          -- top, left to right
  nid:=nid+1;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled)
   VALUES(nid,site,rev,'OHT-M'||lpad((COALESCE(array_length(ring,1),0)+1)::text,3,'0'),20000+i*step,20000,TRUE);
  ring:=ring||nid;
 END LOOP;
 FOR i IN 1..8 LOOP           -- right side, top to bottom
  nid:=nid+1;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled)
   VALUES(nid,site,rev,'OHT-M'||lpad((COALESCE(array_length(ring,1),0)+1)::text,3,'0'),20000+19*step,20000+i*step,TRUE);
  ring:=ring||nid;
 END LOOP;
 FOR i IN 0..19 LOOP          -- bottom, right to left
  nid:=nid+1;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled)
   VALUES(nid,site,rev,'OHT-M'||lpad((COALESCE(array_length(ring,1),0)+1)::text,3,'0'),20000+(19-i)*step,20000+8*step,TRUE);
  ring:=ring||nid;
 END LOOP;
 FOR i IN 1..7 LOOP           -- left side, bottom to top
  nid:=nid+1;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled)
   VALUES(nid,site,rev,'OHT-M'||lpad((COALESCE(array_length(ring,1),0)+1)::text,3,'0'),20000,20000+(8-i)*step,TRUE);
  ring:=ring||nid;
 END LOOP;

 -- One-way, and the four corners are curves: a rail vehicle does not turn on the spot.
 FOR i IN 1..array_length(ring,1) LOOP
  eid:=eid+1;
  INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
   VALUES(eid,site,rev,ring[i],ring[CASE WHEN i=array_length(ring,1) THEN 1 ELSE i+1 END],step,TRUE,
    CASE WHEN i IN (20,28,48) OR i=array_length(ring,1) THEN curve ELSE 0 END);
 END LOOP;

 -- ---- OHT work bays: a spur off the ring that rejoins it, with the work position in the middle ----
 FOR b IN 0..9 LOOP
  at:=2+b*5;                                  -- every fifth main-line node, skipping the corners
  IF at+2>array_length(ring,1) THEN CONTINUE; END IF;
  -- Inside the ring for the top run, inside for the bottom run too: the bays face the yard.
  nid:=nid+1; entry:=nid;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled) VALUES(nid,site,rev,
   'OHT-B'||lpad((b+1)::text,2,'0')||'-IN',
   (SELECT x_mm FROM topology.node WHERE id=ring[at]),
   (SELECT y_mm+CASE WHEN y_mm<50000 THEN 5000 ELSE -5000 END FROM topology.node WHERE id=ring[at]),TRUE);
  nid:=nid+1; mid:=nid;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled) VALUES(nid,site,rev,
   'OHT-B'||lpad((b+1)::text,2,'0'),
   (SELECT x_mm+step/2 FROM topology.node WHERE id=ring[at]),
   (SELECT y_mm+CASE WHEN y_mm<50000 THEN 9000 ELSE -9000 END FROM topology.node WHERE id=ring[at]),TRUE);
  nid:=nid+1; leave:=nid;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled) VALUES(nid,site,rev,
   'OHT-B'||lpad((b+1)::text,2,'0')||'-OUT',
   (SELECT x_mm FROM topology.node WHERE id=ring[at+2]),
   (SELECT y_mm+CASE WHEN y_mm<50000 THEN 5000 ELSE -5000 END FROM topology.node WHERE id=ring[at+2]),TRUE);
  eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
   VALUES(eid,site,rev,ring[at],entry,4000,TRUE,curve);
  eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
   VALUES(eid,site,rev,entry,mid,5000,TRUE,curve);
  eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
   VALUES(eid,site,rev,mid,leave,5000,TRUE,curve);
  eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
   VALUES(eid,site,rev,leave,ring[at+2],4000,TRUE,curve);
  -- The work position is a port: half of them hand work over, half take it back.
  pid:=pid+1;
  INSERT INTO topology.port(id,site_id,revision_id,code,port_type,access_vehicle_type,linked_node_id)
   VALUES(pid,site,rev,'BAY-O'||lpad((b+1)::text,2,'0'),
    CASE WHEN b%2=0 THEN 'LOAD' ELSE 'UNLOAD' END,'OHT',mid);
 END LOOP;

 -- ---- OHT inspection bay: off the line, rejoining it, and a maintenance zone so nothing pulls a
 -- vehicle back out of it. Four standing places, because a plant inspects more than one at a time.
 at:=array_length(ring,1)-5;
 stem:=NULL;
 FOR i IN 1..4 LOOP
  nid:=nid+1;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled)
   VALUES(nid,site,rev,'OHT-INSP'||i,8000,20000+(i+1)*step,TRUE);
  IF stem IS NULL THEN
   eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
    VALUES(eid,site,rev,ring[at],nid,6000,TRUE,curve);
  ELSE
   eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
    VALUES(eid,site,rev,stem,nid,step,TRUE,0);
  END IF;
  stem:=nid;
 END LOOP;
 eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
  VALUES(eid,site,rev,stem,ring[at+3],6000,TRUE,curve);
 INSERT INTO topology.zone(id,site_id,revision_id,code,capacity,kind,enabled)
  VALUES(zid,site,rev,'INSPECTION',4,'MAINTENANCE',TRUE);
 INSERT INTO topology.zone_node(zone_id,site_id,revision_id,node_id)
  SELECT zid,site,rev,id FROM topology.node WHERE site_id=site AND revision_id=rev AND code LIKE 'OHT-INSP%';

 -- ---- OHT waiting places: a spur of its own, so a vehicle with nothing to do is off the line ----
 at:=24;
 stem:=NULL;
 FOR i IN 1..4 LOOP
  nid:=nid+1;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled,wait_for)
   VALUES(nid,site,rev,'OHT-WAIT'||i,20000+(20+i)*step,20000+3*step,TRUE,'OHT');
  IF stem IS NULL THEN
   eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
    VALUES(eid,site,rev,ring[at],nid,6000,TRUE,curve);
  ELSE
   eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
    VALUES(eid,site,rev,stem,nid,step,TRUE,0);
  END IF;
  stem:=nid;
 END LOOP;
 eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
  VALUES(eid,site,rev,stem,ring[at+2],6000,TRUE,curve);

 -- ---- AGV loop: its own track, below the OHT line, with bays, chargers and waiting places ----
 FOR i IN 0..13 LOOP
  nid:=nid+1;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled)
   VALUES(nid,site,rev,'AGV-M'||lpad((COALESCE(array_length(agv,1),0)+1)::text,3,'0'),20000+i*step,105000,TRUE);
  agv:=agv||nid;
 END LOOP;
 FOR i IN 1..5 LOOP
  nid:=nid+1;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled)
   VALUES(nid,site,rev,'AGV-M'||lpad((COALESCE(array_length(agv,1),0)+1)::text,3,'0'),20000+13*step,105000+i*step,TRUE);
  agv:=agv||nid;
 END LOOP;
 FOR i IN 0..13 LOOP
  nid:=nid+1;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled)
   VALUES(nid,site,rev,'AGV-M'||lpad((COALESCE(array_length(agv,1),0)+1)::text,3,'0'),20000+(13-i)*step,105000+5*step,TRUE);
  agv:=agv||nid;
 END LOOP;
 FOR i IN 1..4 LOOP
  nid:=nid+1;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled)
   VALUES(nid,site,rev,'AGV-M'||lpad((COALESCE(array_length(agv,1),0)+1)::text,3,'0'),20000,105000+(5-i)*step,TRUE);
  agv:=agv||nid;
 END LOOP;
 FOR i IN 1..array_length(agv,1) LOOP
  eid:=eid+1;
  INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
   VALUES(eid,site,rev,agv[i],agv[CASE WHEN i=array_length(agv,1) THEN 1 ELSE i+1 END],step,TRUE,
    CASE WHEN i IN (14,19,33) OR i=array_length(agv,1) THEN curve ELSE 0 END);
 END LOOP;

 -- AGV work bays: same spur-and-rejoin shape, with the work position on the spur.
 FOR b IN 0..5 LOOP
  at:=2+b*5;
  IF at+2>array_length(agv,1) THEN CONTINUE; END IF;
  nid:=nid+1; mid:=nid;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled) VALUES(nid,site,rev,
   'AGV-B'||lpad((b+1)::text,2,'0'),
   (SELECT x_mm+step/2 FROM topology.node WHERE id=agv[at]),
   (SELECT y_mm+CASE WHEN y_mm<120000 THEN 6000 ELSE -6000 END FROM topology.node WHERE id=agv[at]),TRUE);
  eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
   VALUES(eid,site,rev,agv[at],mid,5000,TRUE,curve);
  eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
   VALUES(eid,site,rev,mid,agv[at+2],5000,TRUE,curve);
  pid:=pid+1;
  INSERT INTO topology.port(id,site_id,revision_id,code,port_type,access_vehicle_type,linked_node_id)
   VALUES(pid,site,rev,'BAY-A'||lpad((b+1)::text,2,'0'),
    CASE WHEN b%2=0 THEN 'LOAD' ELSE 'UNLOAD' END,'AGV',mid);
 END LOOP;

 -- Chargers on one spur, waiting places on another: a vehicle waiting must not sit on a charger.
 at:=array_length(agv,1)-8;
 stem:=NULL;
 FOR i IN 1..4 LOOP
  nid:=nid+1;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled,charge_point)
   VALUES(nid,site,rev,'AGV-CHG'||i,8000,105000+i*step,TRUE,TRUE);
  chg:=chg||nid;
  IF stem IS NULL THEN
   eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
    VALUES(eid,site,rev,agv[at],nid,6000,TRUE,curve);
  ELSE
   eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
    VALUES(eid,site,rev,stem,nid,step,TRUE,0);
  END IF;
  stem:=nid;
 END LOOP;
 eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
  VALUES(eid,site,rev,stem,agv[at+4],6000,TRUE,curve);

 at:=8;
 stem:=NULL;
 FOR i IN 1..4 LOOP
  nid:=nid+1;
  INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled,wait_for)
   VALUES(nid,site,rev,'AGV-WAIT'||i,20000+(7+i)*step,105000-7000,TRUE,'AGV');
  IF stem IS NULL THEN
   eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
    VALUES(eid,site,rev,agv[at],nid,6000,TRUE,curve);
  ELSE
   eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
    VALUES(eid,site,rev,stem,nid,step,TRUE,0);
  END IF;
  stem:=nid;
 END LOOP;
 eid:=eid+1; INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)
  VALUES(eid,site,rev,stem,agv[at+2],6000,TRUE,curve);

 -- ---- The fleet: 15 OHTs spread round the main line, 10 AGVs round theirs ----
 FOR i IN 1..15 LOOP
  vid:=vid+1;
  INSERT INTO fleet.vehicle_profile(id,site_id,revision_id,code,v_type,initial_node_id,enabled,
    assignment_enabled,name,speed_mm_s,charging_policy)
   VALUES(vid,site,rev,'OHT-'||lpad((10+i)::text,2,'0'),'OHT',ring[1+(i-1)*3],TRUE,TRUE,
    'OHT '||(10+i),2000,'DISABLED');
 END LOOP;
 -- Each AGV names a charge point of its own as well as being allowed the nearest free one: the
 -- setting insists on a named node, and a vehicle whose own charger is busy still charges.
 FOR i IN 1..10 LOOP
  vid:=vid+1;
  INSERT INTO fleet.vehicle_profile(id,site_id,revision_id,code,v_type,initial_node_id,enabled,
    assignment_enabled,name,speed_mm_s,charging_policy,charge_node_id)
   VALUES(vid,site,rev,'AGV-'||lpad((10+i)::text,2,'0'),'AGV',agv[1+(i-1)*3],TRUE,TRUE,
    'AGV '||(10+i),1000,'NEAREST',chg[1+(i-1)%array_length(chg,1)]);
 END LOOP;
END $$;
