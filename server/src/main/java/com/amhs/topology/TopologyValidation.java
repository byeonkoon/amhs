package com.amhs.topology;
import com.amhs.platform.*;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
public class TopologyValidation {
 private final TopologyQuery query;
 private final org.springframework.context.ApplicationEventPublisher events;
 public TopologyValidation(TopologyQuery query,org.springframework.context.ApplicationEventPublisher events){this.query=query;this.events=events;}
 public void vehicleNodes(long site,long revision,VehicleType type,long initial,Long home,Long charge){
  var map=query.getMap(site,revision);
  for(Long node:Arrays.asList(initial,home,charge))if(node!=null)check(map,node,type,-1);
 }
 public void portNode(long site,long revision,long node,VehicleType type,long excludePort){
  var component=check(query.getMap(site,revision),node,type,excludePort);
  events.publishEvent(new PortChanging(site,revision,type,Set.copyOf(component)));
 }
 private Set<Long> check(MapSnapshot map,long node,VehicleType type,long excludePort){
  ConfigurationException.require(map.nodes().stream().anyMatch(n->n.id()==node),"NODE_OUTSIDE_REVISION");
  // Use the physical connected component, including temporarily disabled edges.
  var visited=new HashSet<Long>();visited.add(node);var todo=new ArrayDeque<Long>();todo.add(node);
  while(!todo.isEmpty()){
   long current=todo.remove();
   for(var e:map.edges()){
    if(e.fromNodeId()==current&&visited.add(e.toNodeId()))todo.add(e.toNodeId());
    if(e.toNodeId()==current&&visited.add(e.fromNodeId()))todo.add(e.fromNodeId());
   }
  }
  ConfigurationException.require(map.ports().stream().filter(p->p.id()!=excludePort&&visited.contains(p.linkedNodeId()))
    .allMatch(p->p.accessVehicleType()==type),"VEHICLE_TYPE_NETWORK_MISMATCH");
  return visited;
 }
}
