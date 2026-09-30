package com.amhs.topology;
import com.amhs.platform.ConfigurationException;
/**
 * A segment's editable parameters. Its endpoints are what the segment is, so changing them is
 * rewiring rather than tuning and is not offered here.
 */
public record EdgeSettings(long fromNodeId,long toNodeId,long cost,boolean enabled){
 public void validate(){
  // Cost feeds route comparison, and a negative one would make the shortest path meaningless.
  ConfigurationException.require(cost>=0,"INVALID_COST");
 }
}
