package com.amhs.fleet;
import com.amhs.platform.VehicleType;
public record VehicleView(long id, String code, VehicleType vType, long currentNodeId, boolean enabled, boolean assignmentEnabled, String state, String positionSource) {}
