package com.amhs.integration;
import java.util.Set;
/**
 * The adapters this build can actually speak to a vehicle with, named by the key a protocol profile
 * carries.
 *
 * <p>A protocol profile says what a vehicle speaks; this says what we have written. The two are
 * separate on purpose: a site may record the protocol of equipment long before an adapter for it
 * exists, and recording it must not make the system claim it can drive it. Configuration may only
 * put a vehicle in use on a key named here.
 */
public final class VehicleAdapters {
 private VehicleAdapters(){ }
 /**
  * One JSON object per line over TCP, this system's own contract, with a controller or gateway in
  * front of the line translating to whatever the vehicles themselves speak.
  * See {@code docs/vehicle-tcp-contract.md}.
  */
 public static final String JSON_LINE_V1="JSON_LINE_V1";
 public static final Set<String> KNOWN=Set.of(JSON_LINE_V1);
}
