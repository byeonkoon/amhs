CREATE SEQUENCE platform.configuration_id AS BIGINT START WITH 10000 INCREMENT BY 1;
CREATE TABLE platform.site_operation(
 site_id BIGINT NOT NULL PRIMARY KEY REFERENCES platform.site(id),
 mode VARCHAR(16) NOT NULL CHECK(mode IN ('MAINTENANCE','RUNNING','RECOVERING')));
INSERT INTO platform.site_operation SELECT id,'MAINTENANCE' FROM platform.site;

ALTER TABLE fleet.vehicle_profile
 ADD COLUMN name VARCHAR(128) NOT NULL DEFAULT '',
 ADD COLUMN width_mm INT NOT NULL DEFAULT 1000,
 ADD COLUMN length_mm INT NOT NULL DEFAULT 1000,
 ADD COLUMN height_mm INT NOT NULL DEFAULT 1000,
 ADD COLUMN home_node_id BIGINT,
 ADD COLUMN charge_node_id BIGINT,
 ADD COLUMN control_authority VARCHAR(24) NOT NULL DEFAULT 'CONTROLLER_MANAGED',
 ADD COLUMN bcr_policy VARCHAR(16) NOT NULL DEFAULT 'DISABLED',
 ADD COLUMN charging_policy VARCHAR(16) NOT NULL DEFAULT 'DISABLED',
 ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
UPDATE fleet.vehicle_profile SET name=code;
ALTER TABLE fleet.vehicle_profile
 ADD CONSTRAINT uq_vehicle_site_id UNIQUE(site_id,id),
 ADD CONSTRAINT ck_vehicle_dimensions CHECK(width_mm>0 AND length_mm>0 AND height_mm>0),
 ADD CONSTRAINT ck_authority CHECK(control_authority IN ('CONTROLLER_MANAGED','VENDOR_MANAGED')),
 ADD CONSTRAINT ck_bcr CHECK(bcr_policy IN ('DISABLED','REQUIRED')),
 ADD CONSTRAINT ck_charging_policy CHECK(charging_policy IN ('DISABLED','HOME','NEAREST')),
 ADD CONSTRAINT ck_oht_charge CHECK(v_type<>'OHT' OR (charging_policy='DISABLED' AND charge_node_id IS NULL)),
 ADD CONSTRAINT ck_charging_node CHECK(charging_policy='DISABLED' OR charge_node_id IS NOT NULL),
 ADD CONSTRAINT fk_vehicle_home FOREIGN KEY(site_id,revision_id,home_node_id) REFERENCES topology.node(site_id,revision_id,id),
 ADD CONSTRAINT fk_vehicle_charge FOREIGN KEY(site_id,revision_id,charge_node_id) REFERENCES topology.node(site_id,revision_id,id);
ALTER TABLE topology.port ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE integration.connection_profile
 ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'CLIENT',
 ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
UPDATE integration.connection_profile SET role='BROKER' WHERE comm_type='MQTT';
ALTER TABLE integration.connection_profile
 ADD CONSTRAINT uq_connection_site UNIQUE(site_id,id),
 ADD CONSTRAINT ck_connection_role CHECK((comm_type='TCP' AND role IN ('CLIENT','SERVER')) OR (comm_type='MQTT' AND role='BROKER'));
CREATE TABLE integration.protocol_profile(
 id BIGINT NOT NULL PRIMARY KEY,site_id BIGINT NOT NULL REFERENCES platform.site(id),
 proto_type VARCHAR(16) NOT NULL CHECK(proto_type IN ('PC','PLC','VDA5050')),
 proto_version VARCHAR(32) NOT NULL,adapter_key VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_protocol_site UNIQUE(site_id,id));
CREATE TABLE integration.vehicle_communication(
 id BIGINT NOT NULL PRIMARY KEY,site_id BIGINT NOT NULL,vehicle_id BIGINT NOT NULL,
 connection_profile_id BIGINT NOT NULL,protocol_profile_id BIGINT NOT NULL,
 manufacturer VARCHAR(64) COLLATE "C" NOT NULL,
 external_vehicle_id VARCHAR(64) COLLATE "C" NOT NULL,
 serial_number VARCHAR(64) COLLATE "C",
 interface_name VARCHAR(64) COLLATE "C",
 enabled BOOLEAN NOT NULL DEFAULT FALSE,version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_vehicle_communication UNIQUE(site_id,vehicle_id),
 CONSTRAINT uq_external_vehicle UNIQUE(site_id,connection_profile_id,external_vehicle_id),
 CONSTRAINT fk_communication_vehicle FOREIGN KEY(site_id,vehicle_id) REFERENCES fleet.vehicle_profile(site_id,id),
 CONSTRAINT fk_communication_connection FOREIGN KEY(site_id,connection_profile_id) REFERENCES integration.connection_profile(site_id,id),
 CONSTRAINT fk_communication_protocol FOREIGN KEY(site_id,protocol_profile_id) REFERENCES integration.protocol_profile(site_id,id),
 CONSTRAINT ck_vda_identity_pair CHECK((serial_number IS NULL AND interface_name IS NULL) OR (serial_number IS NOT NULL AND interface_name IS NOT NULL)));
CREATE UNIQUE INDEX uq_vda_identity ON integration.vehicle_communication(site_id,manufacturer,interface_name,serial_number)
 WHERE serial_number IS NOT NULL AND interface_name IS NOT NULL;
