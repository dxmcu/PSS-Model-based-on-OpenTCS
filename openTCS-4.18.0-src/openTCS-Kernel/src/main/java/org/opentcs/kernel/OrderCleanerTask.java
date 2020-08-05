/**
 * Copyright (c) The openTCS Authors.
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.kernel;

import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import static java.util.Objects.requireNonNull;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.opentcs.components.kernel.OrderSequenceCleanupApproval;
import org.opentcs.components.kernel.TransportOrderCleanupApproval;
import org.opentcs.customizations.kernel.GlobalSyncObject;
import org.opentcs.data.TCSObjectEvent;
import org.opentcs.data.TCSObjectReference;
import org.opentcs.data.model.Location;
import org.opentcs.data.order.OrderSequence;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.data.order.TransportOrderBin;
import org.opentcs.kernel.workingset.TransportOrderBinPool;
import org.opentcs.kernel.workingset.TransportOrderPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A task that periodically removes orders in a final state.
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
class OrderCleanerTask
    implements Runnable {

  /**
   * This class's Logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(OrderCleanerTask.class);
  /**
   * A global object to be used for synchronization within the kernel.
   */
  private final Object globalSyncObject;
  /**
   * Keeps all the transport orders.
   */
  private final TransportOrderPool orderPool;
  /**
   * Keeps all the transport bin orders.
   * created by Henry
   */
  private final TransportOrderBinPool orderBinPool;
  /**
   * Check whether transport orders may be removed.
   */
  private final Set<TransportOrderCleanupApproval> orderCleanupApprovals;
  /**
   * Check whether order sequences may be removed.
   */
  private final Set<OrderSequenceCleanupApproval> sequenceCleanupApprovals;
  /**
   * This class's configuration.
   */
  private final OrderPoolConfiguration configuration;

  /**
   * Creates a new instance.
   *
   * @param kernel The kernel.
   * @param configuration This class's configuration.
   */
  @Inject
  public OrderCleanerTask(@GlobalSyncObject Object globalSyncObject,
                          TransportOrderPool orderPool,
                          Set<TransportOrderCleanupApproval> orderCleanupApprovals,
                          Set<OrderSequenceCleanupApproval> sequenceCleanupApprovals,
                          OrderPoolConfiguration configuration,
                          TransportOrderBinPool orderBinPool) {
    this.globalSyncObject = requireNonNull(globalSyncObject, "globalSyncObject");
    this.orderPool = requireNonNull(orderPool, "orderPool");
    this.orderCleanupApprovals = requireNonNull(orderCleanupApprovals, "orderCleanupApprovals");
    this.sequenceCleanupApprovals = requireNonNull(sequenceCleanupApprovals,
                                                   "sequenceCleanupApprovals");
    this.configuration = requireNonNull(configuration, "configuration");
    this.orderBinPool = requireNonNull(orderBinPool,"orderBinPool");
  }

  public long getSweepInterval() {
    return configuration.sweepInterval();
  }

  @Override
  @SuppressWarnings("deprecation")
  public void run() {
    synchronized (globalSyncObject) {
      LOG.debug("Sweeping order pool...");
      // Candidates that are created before this point of time should be removed.
      long creationTimeThreshold = System.currentTimeMillis() - configuration.sweepAge();

      // Remove all transport orders in a final state that do NOT belong to a sequence and that are
      // older than the threshold.
      for (TransportOrder transportOrder
               : orderPool.getObjectPool().getObjects(TransportOrder.class,
                                                      new OrderApproval(creationTimeThreshold))) {
        orderPool.removeTransportOrder(transportOrder.getReference());
      }

      // Remove all order sequences that have been finished, including their transport orders.
      for (OrderSequence orderSequence
               : orderPool.getObjectPool().getObjects(
              OrderSequence.class,
              new SequenceApproval(creationTimeThreshold))) {
        orderPool.removeFinishedOrderSequenceAndOrders(orderSequence.getReference());
      }
      
      //////// modified by Henry
      for(TransportOrderBin tOrderBin
              : orderBinPool.getObjectPool().getObjects(TransportOrderBin.class)
                  .stream()
                  .filter(tOrderBin -> tOrderBin.getState().isFinalState())
                  .filter(orderB -> orderB.getCreationTime().toEpochMilli()<creationTimeThreshold)
                  .collect(Collectors.toSet()))
        orderBinPool.removeTransportOrderBin(tOrderBin.getReference());
      
      // ��ռ��̨ TEST
      for(Location pickStation 
          : orderBinPool.getObjectPool().getObjects(Location.class, 
                                                    p->p.getType()
                                                        .getName()
                                                        .startsWith(Location.PICK_TYPE_PREFIX))){
        Location previous = pickStation.clone();
        pickStation = orderBinPool.getObjectPool()
                                  .replaceObject(pickStation.withBins(new ArrayList<>()));
        orderBinPool.getObjectPool().emitObjectEvent(pickStation.clone(), 
                                                     previous,
                                                     TCSObjectEvent.Type.OBJECT_MODIFIED);
      }
      //////// modified end
    }
  }

  /**
   * Checks whether a transport order may be removed.
   */
  private class OrderApproval
      implements Predicate<TransportOrder> {

    private final long creationTimeThreshold;

    public OrderApproval(long creationTimeThreshold) {
      this.creationTimeThreshold = creationTimeThreshold;
    }

    @Override
    public boolean test(TransportOrder order) {
      if (!order.getState().isFinalState()) {
        return false;
      }
      if (order.getWrappingSequence() != null) {
        return false;
      }
      if (order.getCreationTime() >= creationTimeThreshold) {
        return false;
      }
      for (TransportOrderCleanupApproval approval : orderCleanupApprovals) {
        if (!approval.test(order)) {
          return false;
        }
      }
      return true;
    }
  }

  /**
   * Checks whether an order sequence may be removed.
   */
  private class SequenceApproval
      implements Predicate<OrderSequence> {

    private final long creationTimeThreshold;

    public SequenceApproval(long creationTimeThreshold) {
      this.creationTimeThreshold = creationTimeThreshold;
    }

    @Override
    public boolean test(OrderSequence seq) {
      if (!seq.isFinished()) {
        return false;
      }
      List<TCSObjectReference<TransportOrder>> orderRefs = seq.getOrders();
      if (!orderRefs.isEmpty()) {
        TransportOrder lastOrder
            = orderPool.getObjectPool().getObject(TransportOrder.class,
                                                  Iterables.getLast(orderRefs));
        if (lastOrder.getCreationTime() >= creationTimeThreshold) {
          return false;
        }
      }
      for (OrderSequenceCleanupApproval approval : sequenceCleanupApprovals) {
        if (!approval.test(seq)) {
          return false;
        }
      }
      return true;
    }
  }
}