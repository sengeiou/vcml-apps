package voldemort.routing;

import voldemort.VoldemortException;
import voldemort.consistency.cluster.Cluster;
import voldemort.store.StoreDefinition;

/**
 * A factory that gets the appropriate {@link RoutingStrategy} for a given
 * {@link RoutingStrategyType}.
 * 
 * 
 */
public class RoutingStrategyFactory {

    public RoutingStrategyFactory() {}

    public RoutingStrategy updateRoutingStrategy(StoreDefinition storeDef, Cluster cluster) {
        if(RoutingStrategyType.CONSISTENT_STRATEGY.equals(storeDef.getRoutingStrategyType())) {
            return new ConsistentRoutingStrategy(cluster,
                                                 storeDef.getReplicationFactor());
        } else if(RoutingStrategyType.TO_ALL_STRATEGY.equals(storeDef.getRoutingStrategyType())) {
            return new RouteToAllStrategy(cluster.getNodesShuffled());
        } else if(RoutingStrategyType.ZONE_STRATEGY.equals(storeDef.getRoutingStrategyType())) {
            return new ZoneRoutingStrategy(cluster,
                                           storeDef.getZoneReplicationFactor(),
                                           storeDef.getReplicationFactor());
        } else if(RoutingStrategyType.TO_ALL_LOCAL_PREF_STRATEGY.equals(storeDef.getRoutingStrategyType())) {
            return new RouteToAllLocalPrefStrategy(cluster.getNodesShuffled());
        } else {
            throw new VoldemortException("RoutingStrategyType:" + storeDef.getRoutingStrategyType()
                                         + " not handled by " + this.getClass());
        }
    }
}
