-- Example connection and protocol profiles so the configuration screen has something to show.
-- All profiles stay disabled: no device adapter is implemented yet.
INSERT INTO integration.connection_profile(id,site_id,comm_type,host,port,enabled,role,version) VALUES
 (1,1,'TCP','127.0.0.1',9001,FALSE,'CLIENT',0),
 (2,1,'TCP','127.0.0.1',9002,FALSE,'SERVER',0),
 (3,1,'MQTT','127.0.0.1',1883,FALSE,'BROKER',0);
INSERT INTO integration.protocol_profile(id,site_id,proto_type,proto_version,adapter_key,version) VALUES
 (1,1,'PC','1.0','pc-serial-v1',0),
 (2,1,'PLC','1.0','plc-addr-v1',0),
 (3,1,'VDA5050','3.0.0','vda5050-v3',0);
