package com.amhs.platform;
public final class ConfigurationInput {
 private ConfigurationInput(){}
 public static String text(String value,int max,String field){
  ConfigurationException.require(value!=null&&!value.isBlank()&&value.length()<=max&&value.equals(value.trim()),"INVALID_"+field);
  return value;
 }
 public static void positive(long value,String field){ConfigurationException.require(value>0,"INVALID_"+field);}
 public static void version(Long v){ConfigurationException.require(v!=null&&v>=0,"VERSION_REQUIRED");}
}
