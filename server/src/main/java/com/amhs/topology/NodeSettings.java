package com.amhs.topology;
import com.amhs.platform.ConfigurationInput;
/**
 * A node's editable parameters. Which revision it belongs to and which segments reach it are
 * structure, not parameters, and are not changed here.
 *
 * <p>{@code stopNode} makes every vehicle halt here before it is given the next run;
 * {@code mergePoint} marks a junction whose entry the controller grants in request order.
 */
public record NodeSettings(String code,long xMm,long yMm,boolean enabled,boolean stopNode,boolean mergePoint){
 public void validate(){
  ConfigurationInput.text(code,64,"CODE");
 }
}
