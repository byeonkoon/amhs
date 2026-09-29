CREATE SCHEMA platform;
GO
CREATE SCHEMA topology;
GO
CREATE SCHEMA fleet;
GO
CREATE SCHEMA integration;
GO
CREATE TABLE platform.site(id BIGINT NOT NULL PRIMARY KEY, code NVARCHAR(64) NOT NULL UNIQUE, name NVARCHAR(128) NOT NULL);
CREATE TABLE topology.map_revision(
 id BIGINT NOT NULL PRIMARY KEY, site_id BIGINT NOT NULL REFERENCES platform.site(id),
 revision INT NOT NULL, status VARCHAR(16) NOT NULL CHECK(status IN ('DRAFT','ACTIVE','RETIRED')),
 CONSTRAINT uq_revision_site UNIQUE(site_id,id), CONSTRAINT uq_revision_number UNIQUE(site_id,revision));
CREATE UNIQUE INDEX uq_active_map ON topology.map_revision(site_id) WHERE status='ACTIVE';
CREATE TABLE topology.node(
 id BIGINT NOT NULL PRIMARY KEY,site_id BIGINT NOT NULL,revision_id BIGINT NOT NULL,code NVARCHAR(64) NOT NULL,
 x_mm DECIMAL(18,3) NOT NULL,y_mm DECIMAL(18,3) NOT NULL,
 CONSTRAINT uq_node_scope UNIQUE(site_id,revision_id,id),CONSTRAINT uq_node_code UNIQUE(site_id,revision_id,code),
 CONSTRAINT fk_node_revision FOREIGN KEY(site_id,revision_id) REFERENCES topology.map_revision(site_id,id));
CREATE TABLE topology.edge(
 id BIGINT NOT NULL PRIMARY KEY,site_id BIGINT NOT NULL,revision_id BIGINT NOT NULL,
 from_node_id BIGINT NOT NULL,to_node_id BIGINT NOT NULL,cost DECIMAL(18,3) NOT NULL CHECK(cost>=0),enabled BIT NOT NULL,
 CONSTRAINT fk_edge_from FOREIGN KEY(site_id,revision_id,from_node_id) REFERENCES topology.node(site_id,revision_id,id),
 CONSTRAINT fk_edge_to FOREIGN KEY(site_id,revision_id,to_node_id) REFERENCES topology.node(site_id,revision_id,id),
 CONSTRAINT ck_edge_nodes CHECK(from_node_id<>to_node_id));
CREATE TABLE topology.port(
 id BIGINT NOT NULL PRIMARY KEY,site_id BIGINT NOT NULL,revision_id BIGINT NOT NULL,code NVARCHAR(64) NOT NULL,
 port_type NVARCHAR(64) NOT NULL,maker_type NVARCHAR(64) NOT NULL,
 access_vehicle_type VARCHAR(8) NOT NULL CHECK(access_vehicle_type IN ('OHT','AGV')),linked_node_id BIGINT NOT NULL,
 CONSTRAINT uq_port_code UNIQUE(site_id,revision_id,code),
 CONSTRAINT fk_port_node FOREIGN KEY(site_id,revision_id,linked_node_id) REFERENCES topology.node(site_id,revision_id,id));
CREATE TABLE fleet.vehicle_profile(
 id BIGINT NOT NULL PRIMARY KEY,site_id BIGINT NOT NULL,revision_id BIGINT NOT NULL,code NVARCHAR(64) NOT NULL,
 v_type VARCHAR(8) NOT NULL CHECK(v_type IN ('OHT','AGV')),initial_node_id BIGINT NOT NULL,
 enabled BIT NOT NULL,assignment_enabled BIT NOT NULL,
 CONSTRAINT uq_vehicle_code UNIQUE(site_id,code),
 CONSTRAINT fk_vehicle_node FOREIGN KEY(site_id,revision_id,initial_node_id) REFERENCES topology.node(site_id,revision_id,id));
CREATE TABLE integration.connection_profile(
 id BIGINT NOT NULL PRIMARY KEY,site_id BIGINT NOT NULL REFERENCES platform.site(id),
 comm_type VARCHAR(8) NOT NULL CHECK(comm_type IN ('TCP','MQTT')),host NVARCHAR(255) NOT NULL,
 port INT NOT NULL CHECK(port BETWEEN 1 AND 65535),enabled BIT NOT NULL DEFAULT 0);
