/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.l2switch.flow;

import org.opendaylight.l2switch.inventory.InventoryReader;
import org.opendaylight.l2switch.util.InstanceIdentifierUtils;
import org.opendaylight.mdsal.binding.api.NotificationService.Listener;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.MacAddress;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeConnectorRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.arp.rev140528.ArpPacketReceived;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.arp.rev140528.arp.packet.received.packet.chain.packet.ArpPacket;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.basepacket.rev140528.packet.chain.grp.PacketChain;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.basepacket.rev140528.packet.chain.grp.packet.chain.packet.RawPacket;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.basepacket.rev140528.packet.chain.grp.packet.chain.packet.raw.packet.RawPacketFields;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.ethernet.rev140528.ethernet.packet.received.packet.chain.packet.EthernetPacket;
import org.opendaylight.yangtools.binding.DataObjectIdentifier;

/**
 * This class listens to certain type of packets and writes a mac to mac flows.
 */
public class ReactiveFlowWriter implements Listener<ArpPacketReceived> {
    private final InventoryReader inventoryReader;
    private final FlowWriterService flowWriterService;

    public ReactiveFlowWriter(InventoryReader inventoryReader, FlowWriterService flowWriterService) {
        this.inventoryReader = inventoryReader;
        this.flowWriterService = flowWriterService;
    }

    /**
     * Checks if a MAC should be considered for flow creation.
     *
     * @param macToCheck
     *            MacAddress to consider
     * @return true if a MacAddess is broadcast or multicast, false if the
     *         MacAddress is unicast (and thus legible for flow creation).
     */

    private boolean ignoreThisMac(MacAddress macToCheck) {
        if (macToCheck == null) {
            return true;
        }
        String[] octets = macToCheck.getValue().split(":");
        short firstByte = Short.parseShort(octets[0], 16);

        /*
         * First bit in first byte for unicast and multicast is 1 Unicast and
         * multicast are handled by flooding, they are not legible for flow
         * creation
         */

        return (firstByte & 1) == 1;
    }

    @Override
    public void onNotification(ArpPacketReceived packetReceived) {
        if (packetReceived == null || packetReceived.getPacketChain() == null) {
            return;
        }

        RawPacketFields rawPacket = null;
        EthernetPacket ethernetPacket = null;
        ArpPacket arpPacket = null;
        for (PacketChain packetChain : packetReceived.getPacketChain()) {
            if (packetChain.getPacket() instanceof RawPacket) {
                rawPacket = ((RawPacket) packetChain.getPacket()).getRawPacketFields();
            } else if (packetChain.getPacket() instanceof EthernetPacket) {
                ethernetPacket = (EthernetPacket) packetChain.getPacket();
            } else if (packetChain.getPacket() instanceof ArpPacket) {
                arpPacket = (ArpPacket) packetChain.getPacket();
            }
        }
        if (rawPacket == null || ethernetPacket == null || arpPacket == null) {
            return;
        }
        MacAddress destMac = ethernetPacket.getDestinationMac();
        if (!ignoreThisMac(destMac)) {
            writeFlows(rawPacket.getIngress(), ethernetPacket.getSourceMac(), ethernetPacket.getDestinationMac());
        }
    }

    /**
     * Invokes flow writer service to write bidirectional mac-mac flows on a
     * switch.
     *
     * @param ingress
     *            The NodeConnector where the payload came from.
     * @param srcMac
     *            The source MacAddress of the packet.
     * @param destMac
     *            The destination MacAddress of the packet.
     */
    public void writeFlows(NodeConnectorRef ingress, MacAddress srcMac, MacAddress destMac) {
    	DataObjectIdentifier<?> doi = (DataObjectIdentifier<?>) ingress.getValue();
        // Use your utility to get the node identifier
        DataObjectIdentifier<Node> nodeId = InstanceIdentifierUtils.firstIdentifierOf(doi, Node.class);

        // Use nodeId in your downstream call
        NodeConnectorRef destNodeConnector = inventoryReader.getNodeConnector(nodeId, destMac);
        if (destNodeConnector != null) {
            flowWriterService.addBidirectionalMacToMacFlows(srcMac, ingress, destMac, destNodeConnector);
        }
    }
}
