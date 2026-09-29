\set ON_ERROR_STOP on
BEGIN;
DO $$
BEGIN
 IF current_database() NOT IN ('amhs_dev','amhs_test') THEN RAISE EXCEPTION 'Use only an AMHS development/test database'; END IF;
 IF (SELECT COUNT(*) FROM public.flyway_schema_history WHERE success AND version IN ('1','2','3')) <> 3 THEN RAISE EXCEPTION 'Required migrations missing'; END IF;
 IF (SELECT COUNT(*) FROM topology.node WHERE site_id=1 AND revision_id=1) <> 6 THEN RAISE EXCEPTION 'Seed count'; END IF;
 BEGIN
  INSERT INTO topology.node VALUES(9999,1,9999,'INVALID',0,0);
  RAISE EXCEPTION 'Cross revision accepted';
 EXCEPTION WHEN foreign_key_violation THEN NULL;
 END;
 BEGIN
  INSERT INTO topology.port(id,site_id,revision_id,code,port_type,maker_type,access_vehicle_type,linked_node_id)
   VALUES(9999,1,1,'INVALID','L','S','TRUCK',101);
  RAISE EXCEPTION 'Invalid type accepted';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
END $$;
ROLLBACK;
\echo 'PASS: migrations, seed, cross-revision FK, vehicle-type CHECK'
