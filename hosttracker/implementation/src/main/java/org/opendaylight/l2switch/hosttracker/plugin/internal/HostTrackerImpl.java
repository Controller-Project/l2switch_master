/*
 * Copyright (c) 2014 André Martins, Colin Dixon, Evan Zeller and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.l2switch.hosttracker.plugin.internal;

import static java.util.Objects.requireNonNull;
import static org.opendaylight.l2switch.hosttracker.plugin.util.Utilities.firstIdentifierOf;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.FluentFuture;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.opendaylight.l2switch.hosttracker.plugin.inventory.Host;
import org.opendaylight.l2switch.hosttracker.plugin.util.Utilities;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.binding.api.DataObjectModification;
import org.opendaylight.mdsal.binding.api.DataTreeChangeListener;
import org.opendaylight.mdsal.binding.api.DataTreeIdentifier;
import org.opendaylight.mdsal.binding.api.DataTreeModification;
import org.opendaylight.mdsal.binding.api.ReadTransaction;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.opendaylight.address.tracker.rev140617.AddressCapableNodeConnector;
import org.opendaylight.yang.gen.v1.urn.opendaylight.address.tracker.rev140617.address.node.connector.Addresses;
import org.opendaylight.yang.gen.v1.urn.opendaylight.host.tracker.rev140624.HostId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.host.tracker.rev140624.HostNode;
import org.opendaylight.yang.gen.v1.urn.opendaylight.host.tracker.rev140624.host.AttachmentPointsBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.Nodes;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.node.NodeConnector;
import org.opendaylight.yang.gen.v1.urn.opendaylight.l2switch.host.tracker.config.rev140528.HostTrackerConfig;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TopologyId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TpId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.Topology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.TopologyKey;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Link;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yangtools.binding.DataObject;
import org.opendaylight.yangtools.binding.DataObjectIdentifier;
import org.opendaylight.yangtools.concepts.Registration;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("rawtypes")
public class HostTrackerImpl implements DataTreeChangeListener<DataObject> {

    private static final Logger LOG = LoggerFactory.getLogger(HostTrackerImpl.class);

    private static final int CPUS = Runtime.getRuntime().availableProcessors();

    private static final String TOPOLOGY_NAME = "flow:1";

    private final DataBroker dataService;
    private final String topologyId;
    private final long hostPurgeInterval;
    private final long hostPurgeAge;

    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(CPUS);

    private final ConcurrentClusterAwareHostHashMap hosts;
    private final ConcurrentClusterAwareLinkHashMap links;
    private final OperationProcessor opProcessor;
    private final Thread processorThread;
    private Registration addrsNodeListenerRegistration;
    private Registration hostNodeListenerRegistration;
    private Registration linkNodeListenerRegistration;

    /**
     * It creates hosts using reference to MD-SAl / toplogy module. For every hostPurgeIntervalInput time interval
     * it requests to purge hosts that are not seen for hostPurgeAgeInput time interval.
     *
     * @param dataService A reference to the MD-SAL
     * @param config Default configuration
     */
    public HostTrackerImpl(final DataBroker dataService, final HostTrackerConfig config) {
        this.dataService = requireNonNull(dataService);
        this.opProcessor = new OperationProcessor(dataService);
        Preconditions.checkArgument(config.getHostPurgeAge() >= 0, "hostPurgeAgeInput must be non-negative");
        Preconditions.checkArgument(config.getHostPurgeInterval() >= 0, "hostPurgeIntervalInput must be non-negative");
        this.hostPurgeAge = config.getHostPurgeAge();
        this.hostPurgeInterval = config.getHostPurgeInterval();
        processorThread = new Thread(opProcessor);
        final String maybeTopologyId = config.getTopologyId();
        if (maybeTopologyId == null || maybeTopologyId.isEmpty()) {
            this.topologyId = TOPOLOGY_NAME;
        } else {
            this.topologyId = maybeTopologyId;
        }
        this.hosts = new ConcurrentClusterAwareHostHashMap(opProcessor, this.topologyId);
        this.links = new ConcurrentClusterAwareLinkHashMap(opProcessor);

        if (hostPurgeInterval > 0) {
            exec.scheduleWithFixedDelay(() -> purgeHostsNotSeenInLast(hostPurgeAge), 0, hostPurgeInterval,
                    TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings("unchecked")
    public void init() {
        processorThread.start();

        InstanceIdentifier<Addresses> addrCapableNodeConnectors =
                InstanceIdentifier.builder(Nodes.class)
                        .child(org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node.class)
                        .child(NodeConnector.class)
                        .augmentation(AddressCapableNodeConnector.class)
                        .child(Addresses.class).build();
        this.addrsNodeListenerRegistration = dataService.registerDataTreeChangeListener(
            DataTreeIdentifier.create(LogicalDatastoreType.OPERATIONAL, addrCapableNodeConnectors),
            // FIXME: add an specialized object instead of going through raw types!
            (DataTreeChangeListener)this);

        InstanceIdentifier<HostNode> hostNodes = InstanceIdentifier.builder(NetworkTopology.class)
                .child(Topology.class, new TopologyKey(new TopologyId(topologyId)))
                .child(Node.class)
                .augmentation(HostNode.class).build();
        this.hostNodeListenerRegistration = dataService.registerDataTreeChangeListener(
            DataTreeIdentifier.create(LogicalDatastoreType.OPERATIONAL, hostNodes),
            // FIXME: add an specialized object instead of going through raw types!
            (DataTreeChangeListener)this);

        InstanceIdentifier<Link> linkIID = InstanceIdentifier.builder(NetworkTopology.class)
                .child(Topology.class, new TopologyKey(new TopologyId(topologyId)))
                .child(Link.class).build();

        this.linkNodeListenerRegistration = dataService.registerDataTreeChangeListener(
            // FIXME: add an specialized object instead of going through raw types!
            DataTreeIdentifier.create(LogicalDatastoreType.OPERATIONAL, linkIID), (DataTreeChangeListener)this);

    }
    @Override
    public void onDataTreeChanged(List<DataTreeModification<DataObject>> changes) {
        exec.submit(() -> {
            for (DataTreeModification<?> change: changes) {
                DataObjectModification<?> rootNode = change.getRootNode();
                final DataObjectIdentifier<?> identifier = change.path();
                switch (rootNode.modificationType()) {
                    case SUBTREE_MODIFIED:
                    case WRITE:
                        onModifiedData(identifier, rootNode);
                        break;
                    case DELETE:
                        onDeletedData(identifier, rootNode);
                        break;
                    default:
                        break;
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void onModifiedData(DataObjectIdentifier<?> iid, DataObjectModification<?> rootNode) {
        final DataObject dataObject = rootNode.getDataAfter();
        if (dataObject instanceof Addresses) {
            packetReceived((Addresses) dataObject, iid);
        } else if (dataObject instanceof Node) {
            hosts.putLocally((DataObjectIdentifier<Node>) iid, Host.createHost((Node) dataObject));
        } else if (dataObject instanceof  Link) {
            links.putLocally((DataObjectIdentifier<Link>) iid, (Link) dataObject);
        }
    }

    @SuppressWarnings("unchecked")
    private void onDeletedData(DataObjectIdentifier<?> iid, DataObjectModification<?> rootNode) {
        if (iid.lastStep().type().equals(NodeConnector.class)) {
            Node node = (Node) rootNode.getDataBefore();
            DataObjectIdentifier<Node> iiN = (DataObjectIdentifier<Node>) iid;
            HostNode hostNode = node.augmentation(HostNode.class);
            if (hostNode != null) {
                hosts.removeLocally(iiN);
            }
        } else if (iid.lastStep().type().equals(Link.class)) {
            // TODO performance improvement here
            DataObjectIdentifier<Link> iiL = (DataObjectIdentifier<Link>) iid;
            links.removeLocally(iiL);
            linkRemoved((DataObjectIdentifier<Link>) iid, (Link) rootNode.getDataBefore());
        }
    }

    public void packetReceived(Addresses addrs, DataObjectIdentifier<?> ii) {

        DataObjectIdentifier<NodeConnector> iinc = firstIdentifierOf(ii,NodeConnector.class);
        DataObjectIdentifier<org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node> iin = firstIdentifierOf(ii,org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node.class);

        FluentFuture<Optional<NodeConnector>> futureNodeConnector;
        FluentFuture<Optional<
                org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node>> futureNode;
        try (ReadTransaction readTx = dataService.newReadOnlyTransaction()) {
            futureNodeConnector = readTx.read(LogicalDatastoreType.OPERATIONAL, iinc);
            futureNode = readTx.read(LogicalDatastoreType.OPERATIONAL, iin);
            readTx.close();
        }
        Optional<NodeConnector> opNodeConnector = null;
        Optional<org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node> opNode = null;
        try {
            opNodeConnector = futureNodeConnector.get();
            opNode = futureNode.get();
        } catch (ExecutionException | InterruptedException e) {
            LOG.warn("Failed to get node connector {}", iinc, e);
        }
        if (opNode != null && opNode.isPresent()
                && opNodeConnector != null && opNodeConnector.isPresent()) {
            processHost(opNode.orElseThrow(), opNodeConnector.orElseThrow(), addrs);
        }
    }

    private void processHost(org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node node,
                             NodeConnector nodeConnector,
                             Addresses addrs) {
        List<Host> hostsToMod = new ArrayList<>();
        List<Host> hostsToRem = new ArrayList<>();
        List<Link> linksToRem = new ArrayList<>();
        List<Link> linksToAdd = new ArrayList<>();
        synchronized (hosts) {
            LOG.trace("Processing nodeConnector: {} ", nodeConnector.getId());
            HostId hostId = Host.createHostId(addrs);
            if (hostId != null) {
                if (isNodeConnectorInternal(nodeConnector)) {
                    LOG.trace("NodeConnector is internal: {} ", nodeConnector.getId());
                    removeNodeConnectorFromHost(hostsToMod, hostsToRem, nodeConnector);
                    hosts.removeAll(hostsToRem);
                    hosts.putAll(hostsToMod);
                } else {
                    LOG.trace("NodeConnector is NOT internal {} ", nodeConnector.getId());
                    Host host = new Host(addrs, nodeConnector);
                    if (hosts.containsKey(host.getId())) {
                        hosts.get(host.getId()).mergeHostWith(host);
                    } else {
                        hosts.put(host.getId(), host);
                    }
                    List<Link> newLinks = hosts.get(host.getId()).createLinks(node);
                    if (newLinks != null) {
                        linksToAdd.addAll(newLinks);
                    }
                    hosts.submit(host.getId());
                }
            }
        }
        writeDataToDataStore(linksToAdd, linksToRem);
    }

    /**
     * It verifies if a given NodeConnector is *internal*. An *internal*
     * NodeConnector is considered to be all NodeConnetors that are NOT attached
     * to hosts created by hosttracker.
     *
     * @param nodeConnector the nodeConnector to check if it is internal or not.
     * @return true if it was found a host connected to this nodeConnetor, false
     *     if it was not found a network topology or it was not found a host connected to this nodeConnetor.
     */
    private boolean isNodeConnectorInternal(NodeConnector nodeConnector) {
        TpId tpId = new TpId(nodeConnector.key().getId().getValue());
        InstanceIdentifier<NetworkTopology> ntII
                = InstanceIdentifier.builder(NetworkTopology.class).build();
        FluentFuture<Optional<NetworkTopology>> lfONT;
        try (ReadTransaction rot = dataService.newReadOnlyTransaction()) {
            lfONT = rot.read(LogicalDatastoreType.OPERATIONAL, ntII);
        }
        Optional<NetworkTopology> optionalNT;
        try {
            optionalNT = lfONT.get();
        } catch (InterruptedException | ExecutionException e) {
            LOG.warn("Failed to get network topology {}", ntII, e);
            return false;
        }
        if (optionalNT.isPresent()) {
            NetworkTopology networkTopo = optionalNT.orElseThrow();
            for (Topology t : networkTopo.nonnullTopology().values()) {
                if (t.getLink() != null) {
                    for (Link l : t.nonnullLink().values()) {
                        if (l.getSource().getSourceTp().equals(tpId)
                                && !l.getDestination().getDestTp().getValue().startsWith(Host.NODE_PREFIX)
                                || l.getDestination().getDestTp().equals(tpId)
                                && !l.getSource().getSourceTp().getValue().startsWith(Host.NODE_PREFIX)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void removeLinksFromHosts(List<Host> hostsToMod, List<Host> hostsToRem, Link linkRemoved) {
        for (Host h : hosts.values()) {
            h.removeTerminationPoint(linkRemoved.getSource().getSourceTp());
            h.removeTerminationPoint(linkRemoved.getDestination().getDestTp());
            if (h.isOrphan()) {
                hostsToRem.add(h);
            } else {
                hostsToMod.add(h);
            }
        }
    }

    private void removeNodeConnectorFromHost(List<Host> hostsToMod, List<Host> hostsToRem, NodeConnector nc) {
        AttachmentPointsBuilder atStD = Utilities.createAPsfromNodeConnector(nc);
        for (Host h : hosts.values()) {
            h.removeAttachmentPoints(atStD);
            if (h.isOrphan()) {
                hostsToRem.add(h);
            } else {
                hostsToMod.add(h);
            }
        }
    }

    private void linkRemoved(DataObjectIdentifier<Link> iiLink, Link linkRemoved) {
        LOG.trace("linkRemoved");
        List<Host> hostsToMod = new ArrayList<>();
        List<Host> hostsToRem = new ArrayList<>();
        synchronized (hosts) {
            removeLinksFromHosts(hostsToMod, hostsToRem, linkRemoved);
            hosts.removeAll(hostsToRem);
            hosts.putAll(hostsToMod);
        }
    }

    private void writeDataToDataStore(List<Link> linksToAdd, List<Link> linksToRemove) {
        if (linksToAdd != null) {
            for (final Link l : linksToAdd) {
                final InstanceIdentifier<Link> lIID = Utilities.buildLinkIID(l.key(), topologyId);
                LOG.trace("Writing link from MD_SAL: {}", lIID.toString());
                opProcessor.enqueueOperation(
                    tx -> tx.mergeParentStructureMerge(LogicalDatastoreType.OPERATIONAL, lIID, l));
            }
        }
        if (linksToRemove != null) {
            for (Link l : linksToRemove) {
                final InstanceIdentifier<Link> lIID = Utilities.buildLinkIID(l.key(), topologyId);
                LOG.trace("Removing link from MD_SAL: {}", lIID.toString());
                opProcessor.enqueueOperation(tx -> tx.delete(LogicalDatastoreType.OPERATIONAL,  lIID));
            }
        }
    }

    /**
     * Remove all hosts that haven't been observed more recently than the specified number of
     * hostsPurgeAgeInSeconds.
     *
     * @param hostsPurgeAgeInSeconds remove hosts that haven't been observed in longer than this number of
     *               hostsPurgeAgeInSeconds.
     */
    protected void purgeHostsNotSeenInLast(final long hostsPurgeAgeInSeconds) {
        int numHostsPurged = 0;
        final long nowInMillis = System.currentTimeMillis();
        final long nowInSeconds = TimeUnit.MILLISECONDS.toSeconds(nowInMillis);
        // iterate through all hosts in the local cache
        for (Host h : hosts.values()) {
            final HostNode hn = h.getHostNode().augmentation(HostNode.class);
            if (hn == null) {
                LOG.warn("Encountered non-host node {} in hosts during purge", h);
            } else if (hn.getAddresses() != null) {
                boolean purgeHosts = false;
                // if the node is a host and has addresses, check to see if it's been seen recently
                purgeHosts = hostReadyForPurge(hn, nowInSeconds,hostsPurgeAgeInSeconds);
                if (purgeHosts) {
                    numHostsPurged = removeHosts(h, numHostsPurged);
                }
            } else {
                LOG.warn("Encountered host node {} with no address in hosts during purge", hn);
            }
        }
        LOG.debug("Number of purged hosts during current purge interval - {}. ", numHostsPurged);
    }

    /**
     * Checks if hosts need to be purged.
     *
     * @param hostNode reference to HostNode class
     * @param currentTimeInSeconds current time in seconds
     * @param expirationPeriod timelimit set to hosts for expiration
     * @return boolean - whether the hosts are ready to be purged
     */
    private static boolean hostReadyForPurge(final HostNode hostNode, final long currentTimeInSeconds,
            final long expirationPeriod) {
        // checks if hosts need to be purged
        for (Addresses addrs : hostNode.nonnullAddresses().values()) {
            long lastSeenTimeInSeconds = addrs.getLastSeen() / 1000;
            if (lastSeenTimeInSeconds > currentTimeInSeconds - expirationPeriod) {
                LOG.debug("Host node {} NOT ready for purge", hostNode);
                return false;
            }
        }
        LOG.debug("Host node {} ready for purge", hostNode);
        return true;
    }

    /**
     * Removes hosts from locally and MD-SAL. Throws warning message if not removed successfully
     *
     * @param host  reference to Host node
     */
    private int removeHosts(final Host host, int numHostsPurged) {
        // remove associated links with the host before removing hosts
        removeAssociatedLinksFromHosts(host);
        // purge hosts from local & MD-SAL database
        final HostId hostId = host.getId();
        if (hosts.remove(hostId) != null) {
            numHostsPurged++;
            LOG.debug("Removed host with id {} during purge.", hostId);
        } else {
            LOG.warn("Unexpected error encountered - Failed to remove host {} during purge", host);
        }

        return numHostsPurged;
    }

    /**
     * Removes links associated with the given hosts from local and MD-SAL database.
     * Throws warning message if not removed successfully.
     *
     * @param host  reference to Host node
     */
    private void removeAssociatedLinksFromHosts(final Host host) {
        if (host.getId() != null) {
            List<Link> linksToRemove = new ArrayList<>();
            for (Link link: links.values()) {
                if (link.toString().contains(host.getId().getValue())) {
                    linksToRemove.add(link);
                }
            }
            links.removeAll(linksToRemove);
        } else {
            LOG.warn("Encountered host with no id , Unexpected host id {}. ", host);
        }
    }

    public void close() {
        processorThread.interrupt();
        this.addrsNodeListenerRegistration.close();
        this.hostNodeListenerRegistration.close();
        this.linkNodeListenerRegistration.close();
        this.exec.shutdownNow();
        this.hosts.clear();
    }
}
