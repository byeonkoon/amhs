package com.amhs.transport;
import com.amhs.platform.VehicleType;
import java.time.OffsetDateTime;
public record TransportOrder(long id,long siteId,long revisionId,String requestSource,String idempotencyKey,
 long sourcePortId,long destinationPortId,VehicleType vehicleType,String carrierCode,int priority,
 OrderStatus status,CarrierLocation carrierLocation,long version,
 OffsetDateTime createdAt,OffsetDateTime startedAt,OffsetDateTime terminalAt,String cancelReason){}
