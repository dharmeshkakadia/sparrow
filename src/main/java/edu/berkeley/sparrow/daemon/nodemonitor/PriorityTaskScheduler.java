package edu.berkeley.sparrow.daemon.nodemonitor;

import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.TreeMap;

import org.apache.log4j.Logger;

import com.google.common.collect.Maps;

/**
 * A {@link TaskScheduler} which launches tasks in strict priority order.
 */
public class PriorityTaskScheduler extends TaskScheduler {
  private final static Logger LOG = Logger.getLogger(PriorityTaskScheduler.class);

  /** Queue of tasks of each priority. */
  private TreeMap<Integer, Queue<TaskSpec>> priorityQueues = Maps.newTreeMap();
  int numQueuedReservations;

  public int maxActiveTasks;
  public Integer activeTasks;

  public PriorityTaskScheduler(int maxActiveTasks) {
    this.maxActiveTasks = maxActiveTasks;
    activeTasks = 0;
    numQueuedReservations = 0;
  }

  @Override
  synchronized int handleSubmitTaskReservation(TaskSpec taskReservation) {
     /* Because of the need to check the free resources and then, depending on the result, start a
      * new task, this method must be synchronized.
      */
    int priority = taskReservation.user.getPriority();
    if (!priorityQueues.containsKey(priority)) {
      priorityQueues.put(priority, new LinkedList<TaskSpec>());
    }

    if (activeTasks < maxActiveTasks) {
      if (numQueuedReservations > 0) {
        String errorMessage = "activeTasks should be less than maxActiveTasks only " +
            "when no outstanding reservations.";
        LOG.error(errorMessage);
        throw new IllegalStateException(errorMessage);
      }
      makeTaskRunnable(taskReservation);
      ++activeTasks;
      LOG.debug("Making task for request " + taskReservation.requestId + " with priority " +
                priority + " runnable (" + activeTasks + " of " + maxActiveTasks +
                " task slots currently filled)");
      return 0;
    }

    LOG.debug("All " + maxActiveTasks + " task slots filled.");
    Queue<TaskSpec> reservations = priorityQueues.get(priority);
    LOG.debug("Adding reservation for priority " + priority + ". " + reservations.size() +
              " reservations already queued for that priority, and " + numQueuedReservations +
              " total reservations queued.");
    reservations.add(taskReservation);
    return ++numQueuedReservations;
  }

  @Override
  protected synchronized void handleTaskCompleted(
      String requestId, String lastExecutedTaskRequestId, String lastExecutedTaskId) {
    if (numQueuedReservations != 0) {
      // Launch a task for the lowest valued priority with queued tasks.
      for (Entry<Integer, Queue<TaskSpec>> entry : priorityQueues.entrySet()) {
        TaskSpec nextTask = entry.getValue().poll();
        if (nextTask != null) {
          LOG.debug("Launching task for request " + nextTask.requestId + " (priority " +
                    entry.getKey() + ")");
          nextTask.previousRequestId = lastExecutedTaskRequestId;
          nextTask.previousTaskId = lastExecutedTaskId;
          makeTaskRunnable(nextTask);
          numQueuedReservations--;
          return;
        }
      }
      String errorMessage = ("numQueuedReservations=" + numQueuedReservations +
          " but no queued tasks found.");
      throw new IllegalStateException(errorMessage);
    } else {
      LOG.debug("No queued tasks, so not launching anything.");
      activeTasks -= 1;
    }
  }

  @Override
  int getMaxActiveTasks() {
    return maxActiveTasks;
  }

}
