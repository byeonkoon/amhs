package com.amhs.platform;
import java.util.Set;
/**
 * Site-wide operation option. The communication type applies to every vehicle of the site;
 * nodeCount is the most nodes one movement authority may cover.
 *
 * <p>MORE extends a running movement authority. NODE issues it when at most moreRemainingNodes
 * authorized nodes remain; DISTANCE when the remaining authorized distance is at most
 * moreRemainingPercent of the distance authorized at the start of the movement.
 */
public record OperationOptions(String commType,int nodeCount,boolean moreEnabled,String moreType,
 int moreRemainingNodes,int moreRemainingPercent){
 public static final Set<String> COMM_TYPES=Set.of("PC","PLC","MQTT");
 public static final Set<String> MORE_TYPES=Set.of("NODE","DISTANCE");
 public boolean usesVda(){return "MQTT".equals(commType);}
 public void validate(){
  ConfigurationException.require(COMM_TYPES.contains(commType==null?"":commType),"INVALID_COMM_TYPE");
  ConfigurationException.require(nodeCount>=1&&nodeCount<=100,"INVALID_NODE_COUNT");
  ConfigurationException.require(MORE_TYPES.contains(moreType==null?"":moreType),"INVALID_MORE_TYPE");
  ConfigurationException.require(moreRemainingNodes>=1&&moreRemainingNodes<=99,"INVALID_MORE_REMAINING_NODES");
  // Otherwise every new authority would already satisfy the threshold and MORE would fire at once.
  ConfigurationException.require(moreRemainingNodes<nodeCount,"MORE_NODES_NOT_BELOW_NODE_COUNT");
  ConfigurationException.require(moreRemainingPercent>=1&&moreRemainingPercent<=99,"INVALID_MORE_REMAINING_PERCENT");
 }
}
