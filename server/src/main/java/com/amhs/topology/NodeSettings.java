package com.amhs.topology;
import com.amhs.platform.ConfigurationInput;
/**
 * A node's editable parameters. Which revision it belongs to and which segments reach it are
 * structure, not parameters, and are not changed here.
 *
 * <p>{@code stopNode} makes every vehicle halt here before it is given the next run;
 * {@code mergePoint} marks a junction whose entry the controller grants in request order;
 * {@code chargePoint} is a place an AGV can charge.
 */
public record NodeSettings(String code,long xMm,long yMm,boolean enabled,boolean stopNode,boolean mergePoint,Boolean chargePoint){
 public NodeSettings{ if(chargePoint==null) chargePoint=false; }
 public NodeSettings(String code,long xMm,long yMm,boolean enabled,boolean stopNode,boolean mergePoint){
  this(code,xMm,yMm,enabled,stopNode,mergePoint,false);
 }
 public void validate(){
  ConfigurationInput.text(code,64,"CODE");
 }
}
