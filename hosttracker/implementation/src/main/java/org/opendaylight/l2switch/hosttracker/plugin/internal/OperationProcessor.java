/*
 * Copyright (c) 2015 Evan Zeller and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.l2switch.hosttracker.plugin.internal;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.opendaylight.l2switch.hosttracker.plugin.util.TransactionChainManager;
import org.opendaylight.mdsal.binding.api.*;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Component(service = OperationProcessor.class)
public class OperationProcessor implements AutoCloseable, Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(OperationProcessor.class);
    private static final int MAX_TRANSACTION_OPERATIONS = 100;
    private static final int OPERATION_QUEUE_DEPTH = 500;
    private static final String TOPOLOGY_MANAGER = "l2-topo-processor";

    private final BlockingQueue<HostTrackerOperation> queue = new LinkedBlockingQueue<>(OPERATION_QUEUE_DEPTH);
    private final Thread thread;
    private final TransactionChainManager transactionChainManager;
    private volatile boolean finishing = false;

    @Inject
    @Activate
    @SuppressFBWarnings(value = "SC_START_IN_CTOR", justification = "Component is fully initialized")
    public OperationProcessor(@Reference final DataBroker dataBroker) {
        transactionChainManager = new TransactionChainManager(dataBroker, TOPOLOGY_MANAGER);
        transactionChainManager.activateTransactionManager();
        transactionChainManager.initialSubmitWriteTransaction();
        thread = new Thread(this);
        thread.setDaemon(true);
        thread.setName("topo -l2");
        thread.start();
        LOG.debug("OperationProcessor started");
    }

    void enqueueOperation(final HostTrackerOperation task) {
        LOG.info("enqueueOperation {}",task);
        try {
            queue.put(task);
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while submitting task {}", task, e);
        }
    }

    @Override
    public void run() {
        while (!finishing) {
            try {
                HostTrackerOperation op = queue.take();

                LOG.debug("New {} operation available, starting transaction", op);

                int ops = 0;
                do {
                    op.applyOperation(transactionChainManager);

                    ops++;
                    if (ops < MAX_TRANSACTION_OPERATIONS) {
                        op = queue.poll();
                    } else {
                        op = null;
                    }

                    LOG.info("Next operation {}", op);
                } while (op != null);

                LOG.info("Processed {} operations, submitting transaction", ops);
                if (!transactionChainManager.submitTransaction()) {
                    cleanDataStoreOperQueue();
                }
            } catch (final InterruptedException e) {
                // This should mean we're shutting down.
                LOG.debug("Stat Manager DS Operation thread interrupted!", e);
                finishing = true;
            }
        }
        // Drain all events, making sure any blocked threads are unblocked
        cleanDataStoreOperQueue();
    }
    private void cleanDataStoreOperQueue() {
        while (!queue.isEmpty()) {
            queue.poll();
        }
    }

    public void close() {
        thread.interrupt();
        try {
            thread.join();
        } catch (InterruptedException e) {
            LOG.debug("Join of thread {} was interrupted", thread.getName(), e);
        }

        transactionChainManager.close();
        LOG.debug("OperationProcessor stopped");
    }

}
