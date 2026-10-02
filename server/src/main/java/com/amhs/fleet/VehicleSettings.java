package com.amhs.fleet;
import com.amhs.platform.*;
public record VehicleSettings(long revisionId,String code,String name,VehicleType vType,int widthMm,int lengthMm,int heightMm,
 long initialNodeId,Long homeNodeId,Long chargeNodeId,boolean enabled,boolean assignmentEnabled,String controlAuthority,
 String bcrPolicy,String chargingPolicy,Long vdaProfileId,Integer speedMmS){
 /** Travel speed used when none is given: a floor vehicle drives slower than one on a rail. */
 public static int defaultSpeed(VehicleType type){ return type==VehicleType.OHT?2000:1000; }
 public VehicleSettings{
  if(speedMmS==null&&vType!=null) speedMmS=defaultSpeed(vType);
 }
 public VehicleSettings(long revisionId,String code,String name,VehicleType vType,int widthMm,int lengthMm,int heightMm,
  long initialNodeId,Long homeNodeId,Long chargeNodeId,boolean enabled,boolean assignmentEnabled,String controlAuthority,
  String bcrPolicy,String chargingPolicy,Long vdaProfileId){
  this(revisionId,code,name,vType,widthMm,lengthMm,heightMm,initialNodeId,homeNodeId,chargeNodeId,enabled,assignmentEnabled,
   controlAuthority,bcrPolicy,chargingPolicy,vdaProfileId,null);
 }
 public void validate(){
  ConfigurationInput.positive(revisionId,"REVISION");ConfigurationInput.positive(initialNodeId,"INITIAL_NODE");
  ConfigurationInput.text(code,64,"CODE");ConfigurationInput.text(name,128,"NAME");
  ConfigurationException.require(vType!=null&&widthMm>0&&lengthMm>0&&heightMm>0,"INVALID_VEHICLE_DIMENSIONS_OR_TYPE");
  ConfigurationException.require(java.util.Set.of("CONTROLLER_MANAGED","VENDOR_MANAGED").contains(controlAuthority==null?"":controlAuthority),"INVALID_AUTHORITY");
  ConfigurationException.require(java.util.Set.of("DISABLED","REQUIRED").contains(bcrPolicy==null?"":bcrPolicy),"INVALID_BCR_POLICY");
  ConfigurationException.require(java.util.Set.of("DISABLED","HOME","NEAREST").contains(chargingPolicy==null?"":chargingPolicy),"INVALID_CHARGING_POLICY");
  ConfigurationException.require(vType!=VehicleType.OHT||("DISABLED".equals(chargingPolicy)&&chargeNodeId==null),"OHT_CHARGING_NOT_SUPPORTED");
  ConfigurationException.require("DISABLED".equals(chargingPolicy)||chargeNodeId!=null,"CHARGE_NODE_REQUIRED");
  ConfigurationException.require(vdaProfileId==null||vdaProfileId>0,"INVALID_VDA_PROFILE");
  ConfigurationException.require(speedMmS!=null&&speedMmS>=50&&speedMmS<=10000,"INVALID_SPEED");
 }
}
