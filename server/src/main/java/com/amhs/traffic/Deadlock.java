package com.amhs.traffic;
import java.util.List;
/** A closed chain of vehicles, each waiting for a node the next one holds. */
public record Deadlock(List<Long> vehicleIds, List<Long> wantedNodeIds) {
    public Deadlock { vehicleIds=List.copyOf(vehicleIds); wantedNodeIds=List.copyOf(wantedNodeIds); }
}
