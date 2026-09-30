package com.amhs.fleet;
/** One state report about a vehicle. Null nodeId means the position is unknown, not unchanged. */
public record VehicleReport(Long nodeId, boolean online, boolean remote, boolean idle,
                            boolean carrierPresent, String source) {}
