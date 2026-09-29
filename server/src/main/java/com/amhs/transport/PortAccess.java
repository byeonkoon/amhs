package com.amhs.transport;
import com.amhs.platform.VehicleType;
/** Minimum port facts the transport module needs to accept an order. */
public record PortAccess(long id,String code,VehicleType accessVehicleType){}
