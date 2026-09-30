-- Operation option: the communication type is chosen once per site, not per vehicle.
-- node_count bounds how many nodes one movement authority may cover.
ALTER TABLE platform.site_operation
 ADD COLUMN comm_type VARCHAR(8) NOT NULL DEFAULT 'PC' CHECK(comm_type IN ('PC','PLC','MQTT')),
 ADD COLUMN node_count INT NOT NULL DEFAULT 20 CHECK(node_count BETWEEN 1 AND 100),
 ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- A VDA profile groups the packet and action settings shared by vehicles of one make and version.
-- Topic version and JSON version are separate: a v2 topic can carry a 1.0.0 payload.
CREATE TABLE integration.vda_profile(
 id BIGINT NOT NULL PRIMARY KEY,site_id BIGINT NOT NULL REFERENCES platform.site(id),
 code VARCHAR(32) COLLATE "C" NOT NULL,
 interface_name VARCHAR(64) COLLATE "C" NOT NULL,
 topic_version VARCHAR(8) COLLATE "C" NOT NULL,
 manufacturer VARCHAR(64) COLLATE "C" NOT NULL,
 json_version VARCHAR(16) COLLATE "C" NOT NULL,
 source_action VARCHAR(64) COLLATE "C" NOT NULL,
 dest_action VARCHAR(64) COLLATE "C" NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_vda_profile_code UNIQUE(site_id,code),
 CONSTRAINT uq_vda_profile_site UNIQUE(site_id,id));

-- A vehicle names at most one VDA profile. It stays empty when the site does not use MQTT.
ALTER TABLE fleet.vehicle_profile
 ADD COLUMN vda_profile_id BIGINT,
 ADD CONSTRAINT fk_vehicle_vda_profile FOREIGN KEY(site_id,vda_profile_id) REFERENCES integration.vda_profile(site_id,id);

-- Synthetic samples: one v3 profile and one v2-topic profile carrying a 1.0.0 payload.
INSERT INTO integration.vda_profile(id,site_id,code,interface_name,topic_version,manufacturer,json_version,source_action,dest_action,version) VALUES
 (1,1,'SAMPLE_V3','vda5050','v3','SAMPLE-MAKER','3.0.0','pick','drop',0),
 (2,1,'SAMPLE_V2','vda5050','v2','SAMPLE-MAKER','1.0.0','pick','drop',0);
