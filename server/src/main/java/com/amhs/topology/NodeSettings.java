package com.amhs.topology;
import com.amhs.platform.ConfigurationException;
import com.amhs.platform.ConfigurationInput;
/**
 * A node's editable parameters. Which revision it belongs to and which segments reach it are
 * structure, not parameters, and are not changed here.
 *
 * <p>{@code stopNode} makes every vehicle halt here before it is given the next run;
 * {@code mergePoint} marks a junction whose entry the controller grants in request order;
 * {@code chargePoint} is a place an AGV can charge; {@code waitFor} names the kind of vehicle --
 * AGV or OHT -- that may stand here with nothing to do, and is null when none may.
 */
public record NodeSettings(String code,long xMm,long yMm,boolean enabled,boolean stopNode,boolean mergePoint,
                           Boolean chargePoint,String waitFor){
 public NodeSettings{
  if(chargePoint==null) chargePoint=false;
  if(waitFor!=null){
   waitFor=waitFor.trim().toUpperCase();
   if(waitFor.isEmpty()) waitFor=null;
  }
 }
 public NodeSettings(String code,long xMm,long yMm,boolean enabled,boolean stopNode,boolean mergePoint,Boolean chargePoint){
  this(code,xMm,yMm,enabled,stopNode,mergePoint,chargePoint,null);
 }
 public NodeSettings(String code,long xMm,long yMm,boolean enabled,boolean stopNode,boolean mergePoint){
  this(code,xMm,yMm,enabled,stopNode,mergePoint,false,null);
 }
 public void validate(){
  ConfigurationInput.text(code,64,"CODE");
  // The two kinds do not share track, so a waiting place belongs to one of them. Anything else is
  // a value the controller would have to guess the meaning of when it looks for somewhere to wait.
  ConfigurationException.require(waitFor==null||"AGV".equals(waitFor)||"OHT".equals(waitFor),"WAIT_FOR_INVALID");
  // A charger is a place to charge, not a place to stand: a vehicle waiting on one is a charger
  // nobody else can use, and that is the thing most worth not doing by accident.
  ConfigurationException.require(!(Boolean.TRUE.equals(chargePoint)&&waitFor!=null),"CHARGE_POINT_NOT_A_WAIT_PLACE");
 }
}
