package com.amhs.platform;
import java.util.Set;
/**
 * Site-wide operation option. The communication type applies to every vehicle of the site;
 * nodeCount is the most nodes one movement authority may cover.
 */
public record OperationOptions(String commType,int nodeCount){
 public static final Set<String> COMM_TYPES=Set.of("PC","PLC","MQTT");
 public boolean usesVda(){return "MQTT".equals(commType);}
 public void validate(){
  ConfigurationException.require(COMM_TYPES.contains(commType==null?"":commType),"INVALID_COMM_TYPE");
  ConfigurationException.require(nodeCount>=1&&nodeCount<=100,"INVALID_NODE_COUNT");
 }
}
