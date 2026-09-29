package com.amhs.topology;
import com.amhs.platform.VehicleType;
import java.util.Set;
/** Synchronous validation event; listener failures roll back the configuration transaction. */
public record PortChanging(long siteId,long revisionId,VehicleType type,Set<Long> component) {}
