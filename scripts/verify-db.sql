SET NOCOUNT ON;
SET XACT_ABORT OFF;
IF DB_NAME() <> N'AMHS_DEV' THROW 51000,'Run only on AMHS_DEV',1;
IF (SELECT COUNT(*) FROM dbo.flyway_schema_history WHERE success=1 AND version IN ('1','2','3')) <> 3 THROW 51001,'Required migrations missing',1;
IF (SELECT COUNT(*) FROM topology.node) <> 6 THROW 51002,'Seed count',1;
BEGIN TRANSACTION;
BEGIN TRY
 INSERT topology.node VALUES(9999,1,9999,N'INVALID',0,0);
 THROW 51003,'Cross revision accepted',1;
END TRY
BEGIN CATCH
 IF ERROR_NUMBER() <> 547 BEGIN
  IF @@TRANCOUNT>0 ROLLBACK TRANSACTION;
  THROW;
 END;
END CATCH;
IF @@TRANCOUNT>0 ROLLBACK TRANSACTION;
BEGIN TRANSACTION;
BEGIN TRY
 INSERT topology.port(id,site_id,revision_id,code,port_type,maker_type,access_vehicle_type,linked_node_id) VALUES(9999,1,1,N'INVALID',N'L',N'S','TRUCK',101);
 THROW 51004,'Invalid type accepted',1;
END TRY
BEGIN CATCH
 IF ERROR_NUMBER() <> 547 BEGIN
  IF @@TRANCOUNT>0 ROLLBACK TRANSACTION;
  THROW;
 END;
END CATCH;
IF @@TRANCOUNT>0 ROLLBACK TRANSACTION;
PRINT 'PASS: migrations, seed, cross-revision FK, vehicle-type CHECK';
