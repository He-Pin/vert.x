/*
 * Copyright (c) 2011-2021 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.core.net.impl.pool;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.PlatformDependent;

import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock free executor.
 *
 * When a thread submits an action, it will enqueue the action to execute and then try to acquire
 * a lock. When the lock is acquired it will execute all the tasks in the queue until empty and then
 * release the lock.
 */
public class CombinerExecutor<S> implements Executor<S> {

  private final Queue<Action<S>> q = PlatformDependent.newMpscQueue();
  private final AtomicInteger s = new AtomicInteger();
  private final S state;

  protected final class InProgressTail {
    Task task;
  }

  private final FastThreadLocal<InProgressTail> current = new FastThreadLocal<>();

  public CombinerExecutor(S state) {
    this.state = state;
  }

  @Override
  public void submit(Action<S> action) {
    q.add(action);
    if (s.get() != 0 || !s.compareAndSet(0, 1)) {
      return;
    }
    Task head = null;
    Task tail = null;
    do {
      try {
        final Action<S> a = q.poll();
        if (a == null) {
          break;
        }
        Task task = a.execute(state);
        if (task != null) {
          if (head == null) {
            assert tail == null;
            tail = task;
            head = task;
          } else {
            tail = tail.next(task);
          }
        }
      } finally {
        s.set(0);
      }
    } while (!q.isEmpty() && s.compareAndSet(0, 1));
    if (head != null) {
      InProgressTail inProgress = current.get();
      if (inProgress == null) {
        inProgress = new InProgressTail();
        current.set(inProgress);
        inProgress.task = tail;
        try {
          // from now one cannot trust tail anymore
          head.runNextTasks();
        } finally {
          current.remove();
        }
      } else {
        assert inProgress.task != null;
        Task oldNextTail = inProgress.task.replaceNext(head);
        assert oldNextTail == null;
        inProgress.task = tail;

      }
    }
  }
}
