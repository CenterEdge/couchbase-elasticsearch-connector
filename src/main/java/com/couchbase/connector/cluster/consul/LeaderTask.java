/*
 * Copyright 2018 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.connector.cluster.consul;

import com.couchbase.client.dcp.core.utils.DefaultObjectMapper;
import com.couchbase.client.dcp.deps.com.fasterxml.jackson.databind.JsonNode;
import com.couchbase.connector.cluster.Membership;
import com.couchbase.connector.cluster.consul.rpc.Broadcaster;
import com.couchbase.connector.cluster.consul.rpc.RpcEndpoint;
import com.couchbase.connector.cluster.consul.rpc.RpcResult;
import com.couchbase.connector.config.es.ConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.couchbase.connector.cluster.consul.LeaderEvent.CONFIG_CHANGE;
import static com.couchbase.connector.cluster.consul.LeaderEvent.FATAL_ERROR;
import static com.couchbase.connector.cluster.consul.LeaderEvent.PAUSE;
import static com.couchbase.connector.cluster.consul.LeaderEvent.RESUME;
import static com.couchbase.connector.cluster.consul.ReactorHelper.asCloseable;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class LeaderTask {
  private static final Logger LOGGER = LoggerFactory.getLogger(LeaderTask.class);

  // Wait this long before assuming an unreachable worker node has stopped streaming.
  private static final Duration quietPeriodAfterFailedShutdownRequest = Duration.ofSeconds(30);

  private final ConsulContext ctx;
  private volatile boolean done;
  private volatile Thread thread;

  private final Broadcaster broadcaster = new Broadcaster();

  public LeaderTask(ConsulContext consulContext) {
    this.ctx = requireNonNull(consulContext);
  }

  public LeaderTask start() {
    checkState(thread == null, "Already started.");
    thread = new Thread(this::doRun);
    thread.start();
    return this;
  }

  public void stop() {
    done = true;
    broadcaster.close();
    if (thread != null) {
      thread.interrupt();
    }
    thread = null;
  }

  private void doRun() {
    LOGGER.info("Leader thread started.");

    boolean hasSeenConfig = false;
    boolean hasSeenClusterMembership = false;
    boolean paused = false;

    final BlockingQueue<LeaderEvent> leaderEvents = new LinkedBlockingQueue<>();

    try (Closeable configWatch = asCloseable(subscribeConfig(leaderEvents::add));
         Closeable controlWatch = asCloseable(subscribeControl(leaderEvents::add));
         Closeable membershipWatch = asCloseable(subscribeMembershipEvents(leaderEvents::add))) {
      while (true) {
        throwIfDone();

        final LeaderEvent event = leaderEvents.take();
        LOGGER.info("Got leadership event: {}", event);

        switch (event) {
          case MEMBERSHIP_CHANGE:
            hasSeenClusterMembership = true;
            break;

          case CONFIG_CHANGE:
            hasSeenConfig = true;
            break;

          case PAUSE:
            LOGGER.info("Pausing connector activity.");
            paused = true;
            stopStreaming();
            break;

          case RESUME:
            if (!paused) {
              LOGGER.debug("Ignoring redundant resume signal.");
              continue;
            }
            LOGGER.info("Resuming connector activity.");
            paused = false;
            break;

          case FATAL_ERROR:
            throw new RuntimeException("Fatal error in leader task");
        }

        // don't assign work until we've received at least one cluster membership event
        // and the config document exists.
        if (hasSeenClusterMembership && hasSeenConfig && !paused) {
          LOGGER.info("Rebalance triggered by {}", event);
          rebalance();
        } else {
          if (!hasSeenClusterMembership) {
            LOGGER.info("Waiting for initial cluster membership event before streaming can start.");
          }
          if (!hasSeenConfig) {
            LOGGER.info("Waiting for connector configuration document to exist before streaming can start.");
          }
          if (paused) {
            LOGGER.info("Connector is paused; waiting for 'resume' control signal before streaming can start.");
          }
        }
      }
    } catch (InterruptedException e) {
      // this is how the thread normally terminates.
      LOGGER.debug("Leader thread interrupted", e);

    } catch (Throwable t) {
      LOGGER.error("panic: Leader task failed", t);
      System.exit(1); // todo resign instead of exiting?

    } finally {
      LOGGER.info("Leader thread terminated.");
    }
  }

  private static JsonNode readTreeOrElseEmptyObject(String s) {
    try {
      return DefaultObjectMapper.readTree(isNullOrEmpty(s) ? "{}" : s);
    } catch (IOException e) {
      throw new IllegalArgumentException("Failed to parse JSON", e);
    }
  }

  private void stopStreaming() throws InterruptedException {
    int attempt = 1;

    // Repeat until all endpoints successfully acknowledge they have been shut down
    while (true) {
      throwIfDone();

      final List<RpcEndpoint> endpoints = ctx.rpcEndpoints();
      final Map<RpcEndpoint, RpcResult<Void>> stopResults = broadcaster.broadcast("stop", endpoints, WorkerService.class, WorkerService::stopStreaming);

      if (stopResults.entrySet().stream()
          .noneMatch(e -> e.getValue().isFailed())) {
        if (attempt != 1) {
          LOGGER.warn("Multiple attempts were required to quiesce the cluster. Sleeping for an additional {} to allow unreachable nodes to terminate.", quietPeriodAfterFailedShutdownRequest);
          sleep(quietPeriodAfterFailedShutdownRequest);
        }

        LOGGER.info("Cluster quiesced.");
        return;
      }

      LOGGER.warn("Attempt #{} to quiesce the cluster failed. Will retry.", attempt);

      attempt++;
      SECONDS.sleep(5);
    }
  }

  private static void sleep(Duration d) throws InterruptedException {
    MILLISECONDS.sleep(Math.max(1, d.toMillis()));
  }

  /**
   * Returns all ready endpoints. Blocks until at least one endpoint is ready.
   */
  public List<RpcEndpoint> awaitReadyEndpoints() throws InterruptedException {
    while (true) {
      throwIfDone();

      final List<RpcEndpoint> allEndpoints = ctx.rpcEndpoints();

      final List<RpcEndpoint> readyEndpoints = allEndpoints.stream()
          .filter(rpcEndpoint -> {
            try {
              rpcEndpoint.service(WorkerService.class).ready();
              return true;
            } catch (Throwable t) {
              LOGGER.warn("Endpoint {} is not ready; excluding it from rebalance.", rpcEndpoint, t);
              return false;
            }
          }).collect(Collectors.toList());

      if (!readyEndpoints.isEmpty()) {
        return readyEndpoints;
      }

      // todo truncated exponential backoff with a longer sleep time?
      SECONDS.sleep(5);
    }
  }

  private void rebalance() throws InterruptedException {
    final String config = ctx.readConfig();

    // Sanity check, validate the config.
    ConnectorConfig.from(config);

    restartRebalance:
    while (true) {
      LOGGER.info("Rebalancing the cluster");
      // dumb strategy: shut everything down, then reassign vbuckets
      stopStreaming();

      final List<RpcEndpoint> endpoints = awaitReadyEndpoints();

      for (int i = 0; i < endpoints.size(); i++) {
        throwIfDone();

        final int memberNumber = i + 1;
        final int clusterSize = endpoints.size();
        final Membership membership = Membership.of(memberNumber, clusterSize);

        final RpcEndpoint endpoint = endpoints.get(i);
        LOGGER.info("Assigning group membership {} to endpoint {}", membership, endpoint);
        try {
          endpoint.service(WorkerService.class).startStreaming(membership, config);
        } catch (Throwable t) {
          // todo what happens here? What if it fails due to timeout, and the worker is actually doing the work?
          // For now, start the whole rebalance process over again. This is obviously not ideal.
          LOGGER.warn("Failed to assign group membership {} to endpoint {}", membership, endpoint, t);
          SECONDS.sleep(3);
          continue restartRebalance;
        }
      }

      // success!
      return;
    }
  }

  private void throwIfDone() throws InterruptedException {
    if (done) {
      throw new InterruptedException("Leader termination requested.");
    }
  }

  private Disposable subscribeConfig(Consumer<LeaderEvent> eventSink) {
    return ctx.watchConfig()
        .doOnNext(e -> {
          if (e.isPresent()) {
            eventSink.accept(CONFIG_CHANGE);
          }
        })
        .doOnError(e -> {
          LOGGER.error("panic: Config change watcher failed.", e);
          eventSink.accept(FATAL_ERROR);
        })
        .subscribe();
  }

  private Disposable subscribeControl(Consumer<LeaderEvent> eventSink) {
    return ctx.watchControl()
        .doOnNext(e -> {
          LOGGER.debug("Got control document: {}", e);
          final JsonNode control = readTreeOrElseEmptyObject(e.orElse(""));
          if (control.path("paused").asBoolean(false)) {
            eventSink.accept(PAUSE);
          } else {
            eventSink.accept(RESUME);
          }
        })
        .doOnError(e -> {
          LOGGER.error("panic: Control change watcher failed.", e);
          eventSink.accept(FATAL_ERROR);
        })
        .subscribe();
  }

  private Disposable subscribeMembershipEvents(Consumer<LeaderEvent> eventSink) {
    return ctx.watchServiceHealth(Duration.ofSeconds(5))
        .doOnNext(membership -> eventSink.accept(LeaderEvent.MEMBERSHIP_CHANGE))
        .doOnError(e -> {
          LOGGER.error("panic: Service health watcher failed.", e);
          eventSink.accept(FATAL_ERROR);
        })
        .subscribe();
  }
}
