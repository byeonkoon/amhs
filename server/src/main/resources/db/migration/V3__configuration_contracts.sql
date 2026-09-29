CREATE SEQUENCE platform.configuration_id AS BIGINT START WITH 10000 INCREMENT BY 1;
GO
CREATE TABLE platform.site_operation(
 site_id BIGINT NOT NULL PRIMARY KEY REFERENCES platform.site(id),
 mode VARCHAR(16) NOT NULL CHECK(mode IN ('MAINTENANCE','RUNNING','RECOVERING')));
GO
INSERT platform.site_operation SELECT id,'MAINTENANCE' FROM platform.site;
GO

ALTER TABLE fleet.vehicle_profile ADD
 name NVARCHAR(128) NOT NULL CONSTRAINT df_vehicle_name DEFAULT N'',
 width_mm INT NOT NULL CONSTRAINT df_vehicle_width DEFAULT 1000,
 length_mm INT NOT NULL CONSTRAINT df_vehicle_length DEFAULT 1000,
 height_mm INT NOT NULL CONSTRAINT df_vehicle_height DEFAULT 1000,
 home_node_id BIGINT NULL,charge_node_id BIGINT NULL,
 control_authority VARCHAR(24) NOT NULL CONSTRAINT df_authority DEFAULT 'CONTROLLER_MANAGED',
 bcr_policy VARCHAR(16) NOT NULL CONSTRAINT df_bcr DEFAULT 'DISABLED',
 charging_policy VARCHAR(16) NOT NULL CONSTRAINT df_charge DEFAULT 'DISABLED',
 version BIGINT NOT NULL CONSTRAINT df_vehicle_version DEFAULT 0;
GO
UPDATE fleet.vehicle_profile SET name=code;
GO
ALTER TABLE fleet.vehicle_profile ADD
 CONSTRAINT uq_vehicle_site_id UNIQUE(site_id,id),
 CONSTRAINT ck_vehicle_dimensions CHECK(width_mm>0 AND length_mm>0 AND height_mm>0),
 CONSTRAINT ck_authority CHECK(control_authority IN ('CONTROLLER_MANAGED','VENDOR_MANAGED')),
 CONSTRAINT ck_bcr CHECK(bcr_policy IN ('DISABLED','REQUIRED')),
 CONSTRAINT ck_charging_policy CHECK(charging_policy IN ('DISABLED','HOME','NEAREST')),
 CONSTRAINT ck_oht_charge CHECK(v_type<>'OHT' OR (charging_policy='DISABLED' AND charge_node_id IS NULL)),
 CONSTRAINT ck_charging_node CHECK(charging_policy='DISABLED' OR charge_node_id IS NOT NULL),
 CONSTRAINT fk_vehicle_home FOREIGN KEY(site_id,revision_id,home_node_id) REFERENCES topology.node(site_id,revision_id,id),
 CONSTRAINT fk_vehicle_charge FOREIGN KEY(site_id,revision_id,charge_node_id) REFERENCES topology.node(site_id,revision_id,id);
GO
ALTER TABLE topology.port ADD version BIGINT NOT NULL CONSTRAINT df_port_version DEFAULT 0;
GO
ALTER TABLE integration.connection_profile ADD
 role VARCHAR(16) NOT NULL CONSTRAINT df_connection_role DEFAULT 'CLIENT',
 version BIGINT NOT NULL CONSTRAINT df_connection_version DEFAULT 0;
GO
UPDATE integration.connection_profile SET role='BROKER' WHERE comm_type='MQTT';
GO
ALTER TABLE integration.connection_profile ADD
 CONSTRAINT uq_connection_site UNIQUE(site_id,id),
 CONSTRAINT ck_connection_role CHECK((comm_type='TCP' AND role IN ('CLIENT','SERVER')) OR (comm_type='MQTT' AND role='BROKER'));
GO
CREATE TABLE integration.protocol_profile(
 id BIGINT NOT NULL PRIMARY KEY,site_id BIGINT NOT NULL REFERENCES platform.site(id),
 proto_type VARCHAR(16) NOT NULL CHECK(proto_type IN ('PC','PLC','VDA5050')),
 proto_version NVARCHAR(32) NOT NULL,adapter_key NVARCHAR(64) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_protocol_site UNIQUE(site_id,id));
GO
CREATE TABLE integration.vehicle_communication(
 id BIGINT NOT NULL PRIMARY KEY,site_id BIGINT NOT NULL,vehicle_id BIGINT NOT NULL,
 connection_profile_id BIGINT NOT NULL,protocol_profile_id BIGINT NOT NULL,
 manufacturer NVARCHAR(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
 external_vehicle_id NVARCHAR(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
 serial_number NVARCHAR(64) COLLATE Latin1_General_100_BIN2 NULL,
 interface_name NVARCHAR(64) COLLATE Latin1_General_100_BIN2 NULL,
 enabled BIT NOT NULL DEFAULT 0,version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_vehicle_communication UNIQUE(site_id,vehicle_id),
 CONSTRAINT uq_external_vehicle UNIQUE(site_id,connection_profile_id,external_vehicle_id),
 CONSTRAINT fk_communication_vehicle FOREIGN KEY(site_id,vehicle_id) REFERENCES fleet.vehicle_profile(site_id,id),
 CONSTRAINT fk_communication_connection FOREIGN KEY(site_id,connection_profile_id) REFERENCES integration.connection_profile(site_id,id),
 CONSTRAINT fk_communication_protocol FOREIGN KEY(site_id,protocol_profile_id) REFERENCES integration.protocol_profile(site_id,id),
 CONSTRAINT ck_vda_identity_pair CHECK((serial_number IS NULL AND interface_name IS NULL) OR (serial_number IS NOT NULL AND interface_name IS NOT NULL)));
GO
CREATE UNIQUE INDEX uq_vda_identity ON integration.vehicle_communication(site_id,manufacturer,interface_name,serial_number)
 WHERE serial_number IS NOT NULL AND interface_name IS NOT NULL;
GO
