package sapphire.policy.scalability;

import static sapphire.policy.scalability.masterslave.MethodInvocationResponse.ReturnCode;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import sapphire.common.SapphireObjectNotFoundException;
import sapphire.common.SapphireObjectReplicaNotFoundException;
import sapphire.common.Utils;
import sapphire.kernel.common.GlobalKernelReferences;
import sapphire.policy.DefaultSapphirePolicy;
import sapphire.policy.SapphirePolicyConfig;
import sapphire.policy.scalability.masterslave.Lock;
import sapphire.policy.scalability.masterslave.MethodInvocationRequest;
import sapphire.policy.scalability.masterslave.MethodInvocationResponse;
import sapphire.policy.scalability.masterslave.ReplicationRequest;
import sapphire.policy.scalability.masterslave.ReplicationResponse;

/**
 * Base class for LoadBalancedMasterSlave DM
 *
 * @author terryz
 */
public abstract class LoadBalancedMasterSlaveBase extends DefaultSapphirePolicy {
    /** Base implementation of client side policy */
    public abstract static class ClientBase extends DefaultClientPolicy {
        private static Logger logger = Logger.getLogger(ClientBase.class.getName());
        private final AtomicLong SeqGenerator = new AtomicLong();
        private final UUID CLIENT_ID;

        public ClientBase() {
            CLIENT_ID = UUID.randomUUID();
        }

        @Override
        public Object onRPC(String method, ArrayList<Object> params) throws Exception {
            final int MAX_RETRY = 5;
            int retryCnt = 1, waitInMilliseconds = 50;
            GroupBase group = (GroupBase) getGroup();
            MethodInvocationRequest.MethodType type = MethodInvocationRequest.MethodType.MUTABLE;

            do {
                ServerBase server = (ServerBase) getServer();
                if (isImmutable(server.getClass(), method)) {
                    server = group.getRandomServer();
                    type = MethodInvocationRequest.MethodType.IMMUTABLE;
                } else {
                    server = group.getMaster();
                }

                if (server == null) {
                    logger.log(Level.INFO, "failed to get server with {0} MAX_RETRY", retryCnt);
                    Thread.sleep(waitInMilliseconds);
                    waitInMilliseconds <<= 1;
                    continue;
                }

                MethodInvocationRequest request =
                        new MethodInvocationRequest(
                                CLIENT_ID.toString(),
                                SeqGenerator.getAndAdd(1L),
                                method,
                                params,
                                type);

                MethodInvocationResponse response = server.onRPC(request);
                switch (response.getReturnCode()) {
                    case SUCCESS:
                        return response.getResult();
                    case FAILURE:
                        logger.log(
                                Level.WARNING,
                                "failed to execute request {0}: {1}",
                                new Object[] {request, response});
                        throw (Exception) response.getResult();
                    case REDIRECT:
                        Thread.sleep(waitInMilliseconds);
                        waitInMilliseconds <<= 1;
                }
            } while (++retryCnt <= MAX_RETRY);

            throw new Exception(String.format("failed to execute method %s after retries", method));
        }

        private boolean isImmutable(Class clazz, String method) {
            Method[] methods = clazz.getMethods();
            for (Method m : methods) {
                if (m.toGenericString().equals(method)) {
                    return Utils.isImmutableMethod(m);
                }
            }

            return false;
        }
    }

    /** Base implementation of master slave server policy */
    public abstract static class ServerBase extends DefaultServerPolicy {
        /** @return the ID of the server */
        public String getServerId() {
            return $__getKernelOID().toString();
        }

        /** Starts the server */
        public abstract void start();

        /**
         * Handles replication requests from other servers
         *
         * @param request replication request
         * @return replication response
         */
        public abstract ReplicationResponse handleReplication(ReplicationRequest request);

        /**
         * Update the App object with the given object and updates the commit index accordingly.
         *
         * @param object
         * @param largestCommittedIndex
         */
        public abstract void syncObject(Serializable object, long largestCommittedIndex);

        /**
         * Invokes the given request on App object
         *
         * @param request method invocation request
         * @return method invocation response
         */
        public MethodInvocationResponse onRPC(MethodInvocationRequest request) {
            try {
                Object ret =
                        sapphire_getAppObject()
                                .invoke(request.getMethodName(), request.getParams());
                return new MethodInvocationResponse(ReturnCode.SUCCESS, ret);
            } catch (Exception e) {
                return new MethodInvocationResponse(ReturnCode.FAILURE, e);
            }
        }
    }

    /**
     * Base implementation of master/slave group policy
     *
     * <p>TODO (Terry): Make Group Policy highly available. At present group policy has only one
     * instance which does not satisfy the high available requirement.
     */
    public abstract static class GroupBase extends DefaultGroupPolicy {
        private static final int NUM_OF_REPLICAS = 2;
        private Logger logger;
        private Random random = new Random(System.currentTimeMillis());
        private Lock masterLock;
        private Map<String, String> nodeLabels;

        @Override
        public void onCreate(
                String region,
                SapphireServerPolicy server,
                Map<String, SapphirePolicyConfig> configMap)
                throws RemoteException {
            logger = Logger.getLogger(this.getClass().getName());
            super.onCreate(region, server, configMap);
            logger.info(String.format("Creating master and slave instance in region %s", region));

            try {
                List<InetSocketAddress> servers;
                if (region != null && !region.isEmpty()) {
                    servers = GlobalKernelReferences.nodeServer.oms.getServersInRegion(region);
                } else {
                    servers = GlobalKernelReferences.nodeServer.oms.getServers();
                }

                if (servers.size() < NUM_OF_REPLICAS) {
                    logger.warning(
                            String.format(
                                    "Number of kernel servers (%s) is less than "
                                            + "number of replicas (%s). We will run both master replica "
                                            + "and slave replica on the same server which will decrease "
                                            + "availability.",
                                    servers.size(), NUM_OF_REPLICAS));
                }

                logger.info(
                        String.format(
                                "Creating master and slave instances in servers: %s", servers));

                List<InetSocketAddress> unavailable = new ArrayList<InetSocketAddress>();
                InetSocketAddress dest = getAvailable(0, servers, unavailable);
                ServerBase s = (ServerBase) server;
                s.sapphire_pin_to_server(server, dest);
                updateReplicaHostName(s, dest);
                s.start();
                logger.info("Created master on " + dest);

                for (int i = 0; i < NUM_OF_REPLICAS - 1; i++) {
                    dest = getAvailable(i + 1, servers, unavailable);
                    ServerBase replica =
                            (ServerBase) s.sapphire_replicate(s.getProcessedPolicies());
                    s.sapphire_pin_to_server(replica, dest);
                    updateReplicaHostName(replica, dest);
                    removeServer(replica);
                    addServer(replica);
                    replica.start();
                    logger.info("created slave on " + dest);
                }
            } catch (RemoteException e) {
                throw new RuntimeException("failed to create group: " + e, e);
            } catch (NotBoundException e) {
                throw new Error("rmi operation not bound: " + e, e);
            } catch (SapphireObjectNotFoundException e) {
                throw new RuntimeException("Failed to find sapphire object: " + e, e);
            } catch (SapphireObjectReplicaNotFoundException e) {
                throw new RuntimeException("Failed to find sapphire object replica: " + e, e);
            }
        }

        /**
         * Returns a server from the given {@code servers} list but not in {@code unavailable} list.
         * If no available server can be found, then return the {@code index % servers.size()}th
         * server in the {@code servers} list.
         *
         * @param index index of the server
         * @param servers a list of servers
         * @param unavailable a list of unavailable servers
         * @return an available server; or the {@code index % servers.size()}th server in the given
         *     server list if no available server can be found.
         */
        InetSocketAddress getAvailable(
                int index, List<InetSocketAddress> servers, List<InetSocketAddress> unavailable) {
            for (InetSocketAddress s : servers) {
                if (!unavailable.contains(s)) {
                    unavailable.add(s);
                    return s;
                }
            }

            return servers.get(index % servers.size());
        }

        /**
         * Renew lock
         *
         * @param serverId Id of the server
         * @return <code>true</code> if lock renew succeeds; <code>false</code> otherwise
         */
        public boolean renewLock(String serverId) {
            if (serverId == null || serverId.isEmpty()) {
                throw new IllegalArgumentException("server ID not specified");
            }

            if (masterLock == null) {
                return false;
            }

            return masterLock.renew(serverId);
        }

        /**
         * Obtain lock
         *
         * @param serverId the Id of the server
         * @param masterLeaseTimeoutInMillis
         * @return <code>true</code> if lock is granted; <code>false</code> otherwise
         */
        public boolean obtainLock(String serverId, long masterLeaseTimeoutInMillis) {
            if (masterLock == null) {
                masterLock = new Lock(serverId, masterLeaseTimeoutInMillis);
                return true;
            }

            return masterLock.obtain(serverId);
        }

        /** @return master server, or <code>null</code> if no master available */
        public ServerBase getMaster() throws RemoteException {
            // TODO: cache master to avoid calling group policy remotely for RPC
            if (masterLock != null) {
                List<ServerBase> servers = getServerPolicies();
                for (ServerBase s : servers) {
                    try {
                        if (s.getServerId() != null
                                && s.getServerId().equals(masterLock.getClientId())) {
                            return s;
                        }
                    } catch (Throwable e) {
                        logger.log(
                                Level.WARNING,
                                "unable to get master from group: " + e.getMessage());
                    }
                }
            }
            return null;
        }

        public ServerBase getSlave() throws RemoteException {
            ServerBase master = getMaster();
            List<ServerBase> servers = getServerPolicies();
            for (ServerBase s : servers) {
                if (!s.equals(master)) {
                    return s;
                }
            }
            return null;
        }

        private List<ServerBase> getServerPolicies() throws RemoteException {
            ArrayList<SapphireServerPolicy> servers = super.getServers();
            List<ServerBase> result = new ArrayList<ServerBase>();
            for (SapphireServerPolicy s : servers) {
                result.add((ServerBase) s);
            }
            return result;
        }

        public ServerBase getRandomServer() throws RemoteException {
            List<ServerBase> servers = getServerPolicies();
            return servers.get(random.nextInt(Integer.MAX_VALUE) % servers.size());
        }
    }
}
