package com.amhs.platform;
public class ConfigurationException extends RuntimeException {
 private final int status;
 public ConfigurationException(int status,String code){super(code);this.status=status;}
 public int status(){return status;}
 public static void require(boolean ok,String code){if(!ok)throw new ConfigurationException(422,code);}
}
