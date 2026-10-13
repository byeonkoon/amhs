package com.amhs.integration;
import com.amhs.integration.mapping.DeviceMessageMapping;

/**
 * The wire a block of words is carried on, chosen by the name a mapping document gives it.
 *
 * <p>Here rather than inside either link because both of them need it: a vehicle behind a PLC and a
 * stocker behind one are the same arrangement, and the driver is the part they share. A document
 * that names a wire nobody has written is refused when it is read, so by the time anything reaches
 * this the name is one of these.
 */
public final class WordBuses {
 private WordBuses(){ }
 public static WordBus open(String driver,String host,int port,int unitId,int timeoutMs){
  return DeviceMessageMapping.MC.equals(driver)
   ?new McProtocol(host,port,timeoutMs)
   :new ModbusTcp(host,port,unitId,timeoutMs);
 }
}
