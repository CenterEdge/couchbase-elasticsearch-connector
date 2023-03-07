/*
 * Copyright 2019 Couchbase, Inc.
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

package com.couchbase.connector.elasticsearch;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.MutationResult;
import com.couchbase.connector.dcp.CouchbaseHelper;
import com.couchbase.connector.elasticsearch.io.BackoffPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

import static com.couchbase.connector.dcp.CouchbaseHelper.forceKeyToPartition;
import static com.couchbase.connector.testcontainers.Poller.poll;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

class IntegrationTestHelper {
  private static final Logger LOGGER = LoggerFactory.getLogger(IntegrationTestHelper.class);

  private IntegrationTestHelper() {
    throw new AssertionError("not instantiable");
  }

  static void waitForTravelSampleReplication(TestEsClient es) throws TimeoutException, InterruptedException {
    final int airlines = 187;
    final int routes = 24024;

    final int expectedAirlineCount = airlines + routes;
    final int expectedAirportCount = 1968;

    poll().withTimeout(Duration.ofMinutes(5)).until(() -> {
      long count = es.getDocumentCount("airlines");
      LOGGER.info("airline count = {} / {}", count, expectedAirlineCount);
      return count >= expectedAirlineCount;
    });
    poll().until(() -> {
      long count = es.getDocumentCount("airports");
      LOGGER.info("airport count = {} / {}", count, expectedAirportCount);
      return count >= expectedAirportCount;
    });

    SECONDS.sleep(3); // quiet period, make sure no more documents appear in the index

    assertEquals(expectedAirlineCount, es.getDocumentCount("airlines"));
    assertEquals(expectedAirportCount, es.getDocumentCount("airports"));

    // route documents are routed using airlineid field
    final String routeId = "route_10000";
    final String expectedRouting = "airline_137";
    JsonNode route = es.getDocument("airlines", routeId, expectedRouting).orElse(null);
    assertNotNull(route);
    assertEquals(expectedRouting, route.path("_routing").asText());
  }

  static MutationResult upsertWithRetry(Bucket bucket, JsonDocument document) throws Exception {
    return bucket.defaultCollection().upsert(document.id(), document.content());
  }

  static Set<String> upsertOneDocumentToEachVbucket(Bucket bucket, String idPrefix) throws Exception {
    final int numPartitions = CouchbaseHelper.getNumPartitions(bucket);

    final Stopwatch timer = Stopwatch.createStarted();
    final Set<String> ids = new HashSet<>();
    for (int i = 0; i < numPartitions; i++) {
      final int partition = i;
      final String id = forceKeyToPartition(idPrefix, i, numPartitions)
          .orElseThrow(() -> new RuntimeException("failed to force key '" + idPrefix + "' to partition " + partition));

      upsertWithRetry(bucket, JsonDocument.create(id, JsonObject.create()
          .put("magicWord", "xyzzy")
          .put("partition", partition)));
      ids.add(id);
    }

    LOGGER.info("Upserting to {} partitions took {}", numPartitions, timer);

    return ids;
  }

  static void close(AutoCloseable first, AutoCloseable... others) {
    closeQuietly(first);
    for (AutoCloseable c : others) {
      closeQuietly(c);
    }
  }

  static void closeQuietly(AutoCloseable c) {
    try {
      if (c != null) {
        c.close();
      }
    } catch (Exception e) {
      LOGGER.warn("failed to close {}", c, e);
    }
  }

  static <T> T retryUntilSuccess(BackoffPolicy backoffPolicy, Callable<T> lambda) {
    Iterator<Duration> delays = backoffPolicy.iterator();
    while (true) {
      try {
        return lambda.call();
      } catch (Exception e) {
        e.printStackTrace();

        if (delays.hasNext()) {
          try {
            MILLISECONDS.sleep(delays.next().toMillis());
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
          }
        } else {
          throw new RuntimeException(new TimeoutException());
        }
      }
    }
  }
}
