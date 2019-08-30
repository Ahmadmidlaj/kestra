package org.floworc.core.executions;

import lombok.extern.slf4j.Slf4j;
import org.floworc.core.flows.State;
import org.floworc.core.queues.QueueInterface;
import org.floworc.core.queues.QueueMessage;
import org.floworc.core.tasks.FlowableTask;
import org.floworc.core.tasks.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class ExecutionService {
    private QueueInterface<WorkerTask> workerTaskResultQueue;

    public ExecutionService(QueueInterface<WorkerTask> workerTaskResultQueue) {
        this.workerTaskResultQueue = workerTaskResultQueue;
    }

    public Optional<List<TaskRun>> getNexts(Execution execution, List<Task> tasks) {
        if (tasks.size() == 0) {
            throw new IllegalStateException("Invalid execution " + execution.getId() + " on flow " +
                execution.getFlowId() + " with 0 task"
            );
        }

        // first one
        if (execution.getTaskRunList() == null) {
            return Optional.of(tasks.get(0).toTaskRun(execution));
        }

        // find tasks related to current tasks
        List<TaskRun> taskRunList = execution
            .getTaskRunList()
            .stream()
            .filter(taskRun -> tasks
                .stream()
                .anyMatch(task -> task.getId().equals(taskRun.getTaskId()))
            )
            .collect(Collectors.toList());

        // first one on current list
        if (taskRunList.size() == 0) {
            return Optional.of(tasks.get(0).toTaskRun(execution));
        }

        // all done
        long terminatedCount = taskRunList
            .stream()
            .filter(taskRun -> taskRun.getState().isTerninated())
            .count();

        if (terminatedCount == tasks.size()) {
            return Optional.empty();
        }

        // find first running to handle child tasks
        Optional<TaskRun> firstRunning = taskRunList
            .stream()
            .filter(taskRun -> taskRun.getState().isRunning())
            .findFirst();

        if (firstRunning.isPresent()) {
            Task parent = tasks.get(taskRunList.indexOf(firstRunning.get()));
            if (!(parent instanceof FlowableTask) || !((FlowableTask) parent).hasChildTasks()) {
                return Optional.of(new ArrayList<>());
            } else {
                return this.handleChilds(parent, firstRunning.get(), execution);
            }
        }

        // reverse
        ArrayList<TaskRun> reverse = new ArrayList<>(taskRunList);
        Collections.reverse(reverse);

        // find last created
        Optional<TaskRun> lastCreated = reverse
            .stream()
            .filter(taskRun -> taskRun.getState().getCurrent() == State.Type.CREATED)
            .findFirst();

        if (lastCreated.isPresent()) {
            return Optional.of(new ArrayList<>());
        }

        // find last termintated
        Optional<TaskRun> lastTerminated = reverse
            .stream()
            .filter(taskRun -> taskRun.getState().isTerninated())
            .findFirst();

        if (lastTerminated.isPresent()) {
            if (lastTerminated.get().getState().getCurrent() == State.Type.FAILED) {
                log.warn("Must find errors path");
                return Optional.of(new ArrayList<>());
            } else {
                int index = taskRunList.indexOf(lastTerminated.get());

                if (tasks.size() > index - 1) {
                    return Optional.of(tasks
                        .get(index + 1)
                        .toTaskRun(execution)
                    );
                }
            }
        }

        return Optional.of(new ArrayList<>());
    }

    private Optional<List<TaskRun>> handleChilds(Task parent, TaskRun parentTaskRun, Execution execution) {
        if (!(parent instanceof FlowableTask) || !((FlowableTask) parent).hasChildTasks()) {
            throw new IllegalArgumentException("Invalid parent tasks with no childs");
        }

        Optional<List<Task>> childs = ((FlowableTask) parent).getChildTasks(execution);

        // no childs, just continue
        if (childs.isEmpty()) {
            return Optional.of(new ArrayList<>());
        }

        Optional<List<TaskRun>> nexts = this.getNexts(execution, childs.get());

        // all childs are done, continue the main flow
        if (nexts.isEmpty()) {
            WorkerTask workerTask = WorkerTask.builder()
                .taskRun(execution.findTaskRunById(parentTaskRun.getId()).withState(State.Type.SUCCESS))
                .task(parent)
                .build();

            this.workerTaskResultQueue.emit(QueueMessage.<WorkerTask>builder()
                .key(workerTask.getTaskRun().getExecutionId())
                .body(workerTask)
                .build()
            );

            return Optional.of(new ArrayList<>());
        }

        return nexts;
    }
}
