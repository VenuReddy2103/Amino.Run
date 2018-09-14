package sapphire.policy.dht;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TreeSet;
import java.util.logging.Logger;
import sapphire.common.SapphireObjectNotFoundException;
import sapphire.common.SapphireObjectReplicaNotFoundException;
import sapphire.policy.DefaultSapphirePolicy;
import sapphire.policy.SapphirePolicy.SapphireGroupPolicy;
import sapphire.policy.SapphirePolicy.SapphireServerPolicy;
import sapphire.policy.interfaces.dht.DHTInterface;
import sapphire.policy.interfaces.dht.DHTKey;

public class DHTPolicy extends DefaultSapphirePolicy {
    public static class DHTClientPolicy extends DefaultClientPolicy {}

    public static class DHTServerPolicy extends DefaultServerPolicy {
        private static Logger logger = Logger.getLogger(DefaultServerPolicy.class.getName());
        private Map<DHTKey, Object> dhtData = null;
        private static Timer timer = new Timer();
        private Random delayGenerator = new Random();
        private DHTKey key;

        /**
         * Casts a Map<?, ?> to Map<kClass, vClass>
         *
         * @param map
         * @param kClass
         * @param vClass
         * @return
         */
        @SuppressWarnings("unchecked")
        private <K, V> Map<K, V> castMap(Map<?, ?> map, Class<K> kClass, Class<V> vClass) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                kClass.cast(entry.getKey());
                vClass.cast(entry.getValue());
            }
            return (Map<K, V>) map;
        }

        @Override
        public void onCreate(SapphireGroupPolicy group, Annotation[] annotations) {
            super.onCreate(group, annotations);
            try {
                dhtData =
                        castMap(
                                (Map<DHTKey, ?>)
                                        ((DHTInterface) appObject.getObject()).dhtGetData(),
                                DHTKey.class,
                                Object.class);
            } catch (Exception e) {
                logger.severe("Could not cast the dhtData pointer");
                e.printStackTrace();
            }
        }

        @Override
        public Object onRPC(String method, ArrayList<Object> params) throws Exception {
            /* We assume that the first param is the index */
            DHTServerPolicy responsibleNode =
                    ((DHTGroupPolicy) getGroup())
                            .dhtGetResponsibleNode(new DHTKey((String) params.get(0)));
            return responsibleNode.forwardedRPC(method, params);
        }

        public Object forwardedRPC(String method, ArrayList<Object> params) throws Exception {
            return super.onRPC(method, params);
        }

        public void dhtGetKeys(DHTServerPolicy predecessor, DHTKey nodeId) {
            Map<DHTKey, Object> results = predecessor.dhtGetKeys(nodeId);
            dhtPut(results);
        }

        public Map<DHTKey, Object> dhtGetKeys(DHTKey nodeId) {
            return dhtGet(nodeId);
        }

        /* Cleans the data (that could have come from a replica) */
        public void dhtClear() {
            dhtDelete(null);
        }

        /**
         * Given a key, this function must return all the mappings of keys bigger or equal to this
         * key.
         *
         * @param key
         * @return
         */
        private Map<DHTKey, Object> dhtGet(DHTKey key) {
            if (key == null) return dhtData;

            HashMap<DHTKey, Object> m = new HashMap<DHTKey, Object>();
            Iterator<Map.Entry<DHTKey, Object>> dhtIterator = dhtData.entrySet().iterator();
            while (dhtIterator.hasNext()) {
                Map.Entry<DHTKey, Object> entry = dhtIterator.next();
                if (entry.getKey().compareTo(key) >= 0) {
                    m.put(entry.getKey(), entry.getValue());
                }
            }
            return m;
        }

        /**
         * Inserts the given mappings.
         *
         * @param keys
         */
        private void dhtPut(Map<DHTKey, Object> keys) {

            Iterator<Map.Entry<DHTKey, Object>> dhtIterator = keys.entrySet().iterator();
            while (dhtIterator.hasNext()) {
                Map.Entry<DHTKey, Object> entry = dhtIterator.next();
                dhtData.put(entry.getKey(), entry.getValue());
            }
        }
        /**
         * Remove all the mapping with the keys bigger or equal to the given key.
         *
         * @param key
         */
        public void dhtDelete(DHTKey key) {
            if (key == null) {
                dhtData.clear();
                return;
            }

            Iterator<Map.Entry<DHTKey, Object>> dhtIterator = dhtData.entrySet().iterator();
            while (dhtIterator.hasNext()) {
                Map.Entry<DHTKey, Object> entry = dhtIterator.next();
                if (entry.getKey().compareTo(key) >= 0) {
                    dhtIterator.remove();
                }
            }
        }

        public void setKey(DHTKey key) {
            this.key = key;
        }
    }

    public static class DHTGroupPolicy extends DefaultGroupPolicy {
        private static Logger logger = Logger.getLogger(DefaultGroupPolicy.class.getName());
        private TreeSet<DHTNode> nodes;
        private HashMap<String, DHTServerPolicy> servers;
        private Random dhtNodeIdGenerator;

        private class DHTNode implements Comparable<DHTNode>, Serializable {
            public DHTKey id;
            public DHTServerPolicy server;

            public DHTNode(DHTKey id, DHTServerPolicy server) {
                this.id = id;
                this.server = server;
            }

            @Override
            public String toString() {
                return id.toString();
            }

            @Override
            public boolean equals(Object other) {
                DHTNode o = (DHTNode) other;
                if (o == null) return false;
                if (o.id.compareTo(id) == 0) return true;
                return false;
            }

            @Override
            /** @throws NullPointerException if another is null */
            public int compareTo(DHTNode another) {
                return another.id.compareTo(this.id);
            }
        }

        @Override
        public void onCreate(SapphireServerPolicy server, Annotation[] annotations)
                throws RemoteException {
            nodes = new TreeSet<DHTNode>();
            dhtNodeIdGenerator = new Random();
            super.onCreate(server, annotations);
            try {
                ArrayList<String> regions = sapphire_getRegions();
                DHTServerPolicy dhtServer = (DHTServerPolicy) server;

                for (int i = 1; i < regions.size(); i++) {
                    DHTServerPolicy replica = (DHTServerPolicy) dhtServer.sapphire_replicate();
                    replica.sapphire_pin(regions.get(i));
                }
                dhtServer.sapphire_pin(regions.get(0));

            } catch (RemoteException e) {
                e.printStackTrace();
                throw new Error(
                        "Could not create new group policy because the oms is not available.");
            } catch (SapphireObjectNotFoundException e) {
                throw new Error("Failed to find sapphire object.", e);
            } catch (SapphireObjectReplicaNotFoundException e) {
                throw new Error("Failed to find sapphire object replica.", e);
            }
        }
        /**
         * Returns the predecessor of the given node.
         *
         * @param node
         * @return
         */
        private DHTNode dhtGetPredecessor(DHTNode node) {
            DHTNode predecessor = nodes.lower(node);

            if (predecessor == null) predecessor = nodes.last();

            return predecessor;
        }

        @Override
        public void addServer(SapphireServerPolicy server) throws RemoteException {
            super.addServer(server);
            DHTKey id = new DHTKey(Integer.toString(dhtNodeIdGenerator.nextInt(Integer.MAX_VALUE)));
            DHTServerPolicy dhtServer = (DHTServerPolicy) server;
            DHTNode newNode = new DHTNode(id, dhtServer);
            nodes.add(newNode);

            // copy the necessary keys to this new node and remove them from the current one
            newNode.server.dhtClear();
            DHTNode predecessor = dhtGetPredecessor(newNode);
            /* Avoiding dhtGetKeys for the first node. */
            if ((nodes.size() != 1) && (newNode != predecessor)) {
                newNode.server.dhtGetKeys(predecessor.server, predecessor.id);
            }
            newNode.server.setKey(newNode.id);
            logger.info("NODES: " + nodes.toString());
        }

        @Override
        public void removeServer(SapphireServerPolicy server) throws RemoteException {
            super.removeServer(server);
            // TODO: Need to cleanup nodes
        }

        @Override
        public void onFailure(SapphireServerPolicy server) {
            // TODO
        }

        @Override
        public SapphireServerPolicy onRefRequest() {
            // TODO
            return null;
        }

        public DHTServerPolicy dhtGetResponsibleNode(DHTKey key) {
            // TODO more verification; locking?
            DHTNode responsibleNode = null;
            DHTNode temp = new DHTNode(key, null);

            if (nodes.contains(temp)) responsibleNode = nodes.tailSet(temp).first();
            else {
                responsibleNode = nodes.lower(temp);
                if (responsibleNode == null) responsibleNode = nodes.last();
            }

            logger.info("Responsible node for: " + key + " is: " + responsibleNode.id);
            return responsibleNode.server;
        }
    }
}
