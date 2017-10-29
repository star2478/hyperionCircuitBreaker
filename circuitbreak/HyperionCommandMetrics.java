/**
 * Copyright 2012 Netflix, Inc.
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
package com.pingan.testdemo.circuitbreak;

import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.metric.HystrixThreadEventStream;
import com.netflix.hystrix.metric.consumer.CumulativeCommandEventCounterStream;

import com.netflix.hystrix.metric.consumer.RollingCommandEventCounterStream;
import com.netflix.hystrix.metric.consumer.RollingCommandLatencyDistributionStream;
import com.netflix.hystrix.metric.consumer.RollingCommandMaxConcurrencyStream;
import com.netflix.hystrix.metric.consumer.RollingCommandUserLatencyDistributionStream;
import com.netflix.hystrix.strategy.HystrixPlugins;
import com.netflix.hystrix.strategy.eventnotifier.HystrixEventNotifier;
import com.netflix.hystrix.util.HystrixRollingNumberEvent;
import com.pingan.testdemo.circuitbreak.metric.HyperionCommandCompletion;
import com.pingan.testdemo.circuitbreak.metric.HyperionThreadEventStream;
import com.pingan.testdemo.circuitbreak.metric.consumer.HealthCountsStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.functions.Func0;
import rx.functions.Func2;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Used by {@link HystrixCommand} to record metrics.
 */
public class HyperionCommandMetrics extends HyperionMetrics {

    @SuppressWarnings("unused")
    private static final Logger logger = LoggerFactory.getLogger(HyperionCommandMetrics.class);

    private static final HyperionEventType[] ALL_EVENT_TYPES = HyperionEventType.values();

    public static final Func2<long[], HyperionCommandCompletion, long[]> appendEventToBucket = new Func2<long[], HyperionCommandCompletion, long[]>() {
        @Override
        public long[] call(long[] initialCountArray, HyperionCommandCompletion execution) {
            ExecutionResult.EventCounts eventCounts = execution.getEventCounts();
            for (HyperionEventType eventType: ALL_EVENT_TYPES) {
                switch (eventType) {
                    case EXCEPTION_THROWN: break; //this is just a sum of other anyway - don't do the work here
                    default:
                        initialCountArray[eventType.ordinal()] += eventCounts.getCount(eventType);
                        break;
                }
            }
//            System.out.println("--------appendEventToBucket, array:" + Arrays.toString(initialCountArray));
            return initialCountArray;
        }
    };

    public static final Func2<long[], long[], long[]> bucketAggregator = new Func2<long[], long[], long[]>() {
        @Override
        public long[] call(long[] cumulativeEvents, long[] bucketEventCounts) {
            for (HyperionEventType eventType: ALL_EVENT_TYPES) {
                switch (eventType) {
                    case EXCEPTION_THROWN:
                        for (HyperionEventType exceptionEventType: HyperionEventType.EXCEPTION_PRODUCING_EVENT_TYPES) {
                            cumulativeEvents[eventType.ordinal()] += bucketEventCounts[exceptionEventType.ordinal()];
                        }
                        break;
                    default:
                        cumulativeEvents[eventType.ordinal()] += bucketEventCounts[eventType.ordinal()];
                        break;
                }
            }
            return cumulativeEvents;
        }
    };

    // String is HystrixCommandKey.name() (we can't use HystrixCommandKey directly as we can't guarantee it implements hashcode/equals correctly)
    private static final ConcurrentHashMap<String, HyperionCommandMetrics> metrics = new ConcurrentHashMap<String, HyperionCommandMetrics>();

    /**
     * Get or create the {@link HyperionCommandMetrics} instance for a given {@link HyperionCommandKey}.
     * <p>
     * This is thread-safe and ensures only 1 {@link HyperionCommandMetrics} per {@link HyperionCommandKey}.
     * 
     * @param key
     *            {@link HyperionCommandKey} of {@link HystrixCommand} instance requesting the {@link HyperionCommandMetrics}
     * @param commandGroup
     *            Pass-thru to {@link HyperionCommandMetrics} instance on first time when constructed
     * @param properties
     *            Pass-thru to {@link HyperionCommandMetrics} instance on first time when constructed
     * @return {@link HyperionCommandMetrics}
     */
    public static HyperionCommandMetrics getInstance(HyperionCommandKey key, HyperionCommandGroupKey commandGroup, HyperionCommandProperties properties) {
        return getInstance(key, commandGroup, null, properties);
    }

    /**
     * Get or create the {@link HyperionCommandMetrics} instance for a given {@link HyperionCommandKey}.
     * <p>
     * This is thread-safe and ensures only 1 {@link HyperionCommandMetrics} per {@link HyperionCommandKey}.
     *
     * @param key
     *            {@link HyperionCommandKey} of {@link HystrixCommand} instance requesting the {@link HyperionCommandMetrics}
     * @param commandGroup
     *            Pass-thru to {@link HyperionCommandMetrics} instance on first time when constructed
     * @param properties
     *            Pass-thru to {@link HyperionCommandMetrics} instance on first time when constructed
     * @return {@link HyperionCommandMetrics}
     */
    public static HyperionCommandMetrics getInstance(HyperionCommandKey key, HyperionCommandGroupKey commandGroup, HyperionThreadPoolKey threadPoolKey, HyperionCommandProperties properties) {
        // attempt to retrieve from cache first
        HyperionCommandMetrics commandMetrics = metrics.get(key.name());
        if (commandMetrics != null) {
            return commandMetrics;
        } else {
            synchronized (HyperionCommandMetrics.class) {
                HyperionCommandMetrics existingMetrics = metrics.get(key.name());
                if (existingMetrics != null) {
                    return existingMetrics;
                } else {
                    HyperionThreadPoolKey nonNullThreadPoolKey;
//                    if (threadPoolKey == null) {
//                        nonNullThreadPoolKey = HystrixThreadPoolKey.Factory.asKey(commandGroup.name());
//                    } else {
//                        nonNullThreadPoolKey = threadPoolKey;
//                    }
                    nonNullThreadPoolKey = threadPoolKey;
                    HyperionCommandMetrics newCommandMetrics = new HyperionCommandMetrics(key, commandGroup, nonNullThreadPoolKey, properties, HystrixPlugins.getInstance().getEventNotifier());
                    metrics.putIfAbsent(key.name(), newCommandMetrics);
                    return newCommandMetrics;
                }
            }
        }
    }

    /**
     * Get the {@link HyperionCommandMetrics} instance for a given {@link HyperionCommandKey} or null if one does not exist.
     * 
     * @param key
     *            {@link HyperionCommandKey} of {@link HystrixCommand} instance requesting the {@link HyperionCommandMetrics}
     * @return {@link HyperionCommandMetrics}
     */
    public static HyperionCommandMetrics getInstance(HyperionCommandKey key) {
        return metrics.get(key.name());
    }

    /**
     * All registered instances of {@link HyperionCommandMetrics}
     * 
     * @return {@code Collection<HystrixCommandMetrics>}
     */
    public static Collection<HyperionCommandMetrics> getInstances() {
        return Collections.unmodifiableCollection(metrics.values());
    }

    /**
     * Clears all state from metrics. If new requests come in instances will be recreated and metrics started from scratch.
     */
    /* package */ static void reset() {
        for (HyperionCommandMetrics metricsInstance: getInstances()) {
            metricsInstance.unsubscribeAll();
        }
        metrics.clear();
    }

    private final HyperionCommandProperties properties;
    private final HyperionCommandKey key;
    private final HyperionCommandGroupKey group;
    private final HyperionThreadPoolKey threadPoolKey;
    private final AtomicInteger concurrentExecutionCount = new AtomicInteger();

    private HealthCountsStream healthCountsStream;
    private final RollingCommandEventCounterStream rollingCommandEventCounterStream;
    private final CumulativeCommandEventCounterStream cumulativeCommandEventCounterStream;
    private final RollingCommandLatencyDistributionStream rollingCommandLatencyDistributionStream;
    private final RollingCommandUserLatencyDistributionStream rollingCommandUserLatencyDistributionStream;
    private final RollingCommandMaxConcurrencyStream rollingCommandMaxConcurrencyStream;

    /* package */HyperionCommandMetrics(final HyperionCommandKey key, HyperionCommandGroupKey commandGroup, HyperionThreadPoolKey threadPoolKey, HyperionCommandProperties properties, HystrixEventNotifier eventNotifier) {
        super(null);
        this.key = key;
        this.group = commandGroup;
        this.threadPoolKey = threadPoolKey;
        this.properties = properties;
        
        
        healthCountsStream = HealthCountsStream.getInstance(key, properties);
//        rollingCommandEventCounterStream = RollingCommandEventCounterStream.getInstance(key, properties);
//        cumulativeCommandEventCounterStream = CumulativeCommandEventCounterStream.getInstance(key, properties);
//
//        rollingCommandLatencyDistributionStream = RollingCommandLatencyDistributionStream.getInstance(key, properties);
//        rollingCommandUserLatencyDistributionStream = RollingCommandUserLatencyDistributionStream.getInstance(key, properties);
//        rollingCommandMaxConcurrencyStream = RollingCommandMaxConcurrencyStream.getInstance(key, properties);
        rollingCommandEventCounterStream = null;
        cumulativeCommandEventCounterStream = null;

        rollingCommandLatencyDistributionStream = null;
        rollingCommandUserLatencyDistributionStream = null;
        rollingCommandMaxConcurrencyStream = null;
    }

    /* package */ synchronized void resetStream() {
        healthCountsStream.unsubscribe();
        HealthCountsStream.removeByKey(key);
        healthCountsStream = HealthCountsStream.getInstance(key, properties);
    }

    /**
     * {@link HyperionCommandKey} these metrics represent.
     * 
     * @return HystrixCommandKey
     */
    public HyperionCommandKey getCommandKey() {
        return key;
    }

    /**
     * {@link HyperionCommandGroupKey} of the {@link HystrixCommand} these metrics represent.
     *
     * @return HystrixCommandGroupKey
     */
    public HyperionCommandGroupKey getCommandGroup() {
        return group;
    }

    /**
     * {@link HyperionThreadPoolKey} used by {@link HystrixCommand} these metrics represent.
     *
     * @return HystrixThreadPoolKey
     */
    public HyperionThreadPoolKey getThreadPoolKey() {
        return threadPoolKey;
    }

    /**
     * {@link HyperionCommandProperties} of the {@link HystrixCommand} these metrics represent.
     * 
     * @return HystrixCommandProperties
     */
    public HyperionCommandProperties getProperties() {
        return properties;
    }

//    public long getRollingCount(HyperionEventType eventType) {
//        return rollingCommandEventCounterStream.getLatest(eventType);
//    }

//    public long getCumulativeCount(HyperionEventType eventType) {
//        return cumulativeCommandEventCounterStream.getLatest(eventType);
//    }

//    @Override
//    public long getCumulativeCount(HystrixRollingNumberEvent event) {
//        return getCumulativeCount(HyperionEventType.from(event));
//    }

//    @Override
//    public long getRollingCount(HystrixRollingNumberEvent event) {
//        return getRollingCount(HyperionEventType.from(event));
//    }

    /**
     * Retrieve the execution time (in milliseconds) for the {@link HystrixCommand#run()} method being invoked at a given percentile.
     * <p>
     * Percentile capture and calculation is configured via {@link HyperionCommandProperties#metricsRollingStatisticalWindowInMilliseconds()} and other related properties.
     * 
     * @param percentile
     *            Percentile such as 50, 99, or 99.5.
     * @return int time in milliseconds
     */
    public int getExecutionTimePercentile(double percentile) {
        return rollingCommandLatencyDistributionStream.getLatestPercentile(percentile);
    }

    /**
     * The mean (average) execution time (in milliseconds) for the {@link HystrixCommand#run()}.
     * <p>
     * This uses the same backing data as {@link #getExecutionTimePercentile};
     * 
     * @return int time in milliseconds
     */
    public int getExecutionTimeMean() {
        return rollingCommandLatencyDistributionStream.getLatestMean();
    }

    /**
     * Retrieve the total end-to-end execution time (in milliseconds) for {@link HystrixCommand#execute()} or {@link HystrixCommand#queue()} at a given percentile.
     * <p>
     * When execution is successful this would include time from {@link #getExecutionTimePercentile} but when execution
     * is being rejected, short-circuited, or timed-out then the time will differ.
     * <p>
     * This time can be lower than {@link #getExecutionTimePercentile} when a timeout occurs and the backing
     * thread that calls {@link HystrixCommand#run()} is still running.
     * <p>
     * When rejections or short-circuits occur then {@link HystrixCommand#run()} will not be executed and thus
     * not contribute time to {@link #getExecutionTimePercentile} but time will still show up in this metric for the end-to-end time.
     * <p>
     * This metric gives visibility into the total cost of {@link HystrixCommand} execution including
     * the overhead of queuing, executing and waiting for a thread to invoke {@link HystrixCommand#run()} .
     * <p>
     * Percentile capture and calculation is configured via {@link HyperionCommandProperties#metricsRollingStatisticalWindowInMilliseconds()} and other related properties.
     * 
     * @param percentile
     *            Percentile such as 50, 99, or 99.5.
     * @return int time in milliseconds
     */
    public int getTotalTimePercentile(double percentile) {
        return rollingCommandUserLatencyDistributionStream.getLatestPercentile(percentile);
    }

    /**
     * The mean (average) execution time (in milliseconds) for {@link HystrixCommand#execute()} or {@link HystrixCommand#queue()}.
     * <p>
     * This uses the same backing data as {@link #getTotalTimePercentile};
     * 
     * @return int time in milliseconds
     */
    public int getTotalTimeMean() {
        return rollingCommandUserLatencyDistributionStream.getLatestMean();
    }

    public long getRollingMaxConcurrentExecutions() {
        return rollingCommandMaxConcurrencyStream.getLatestRollingMax();
    }

    /**
     * Current number of concurrent executions of {@link HystrixCommand#run()};
     * 
     * @return int
     */
    public int getCurrentConcurrentExecutionCount() {
        return concurrentExecutionCount.get();
    }

//    /* package-private */ void markCommandStart(HyperionCommandKey commandKey, HystrixThreadPoolKey threadPoolKey, HyperionCommandProperties.ExecutionIsolationStrategy isolationStrategy) {
//        int currentCount = concurrentExecutionCount.incrementAndGet();
//        HystrixThreadEventStream.getInstance().commandExecutionStarted(commandKey, threadPoolKey, isolationStrategy, currentCount);
//    }

	/* package-private */ void markCommandDone(ExecutionResult executionResult, HyperionCommandKey commandKey, HyperionThreadPoolKey threadPoolKey, boolean executionStarted) {
        HyperionThreadEventStream.getInstance().executionDone(executionResult, commandKey, threadPoolKey);
        if (executionStarted) {
            concurrentExecutionCount.decrementAndGet();
        }
    }

    /* package-private */ HealthCountsStream getHealthCountsStream() {
        return healthCountsStream;
    }

    /**
     * Retrieve a snapshot of total requests, error count and error percentage.
     *
     * This metrics should measure the actual health of a {@link HystrixCommand}.  For that reason, the following are included:
     * <p><ul>
     * <li>{@link HyperionEventType#SUCCESS}
     * <li>{@link HyperionEventType#FAILURE}
     * <li>{@link HyperionEventType#TIMEOUT}
     * <li>{@link HyperionEventType#THREAD_POOL_REJECTED}
     * <li>{@link HyperionEventType#SEMAPHORE_REJECTED}
     * </ul><p>
     * The following are not included in either attempts/failures:
     * <p><ul>
     * <li>{@link HyperionEventType#BAD_REQUEST} - this event denotes bad arguments to the command and not a problem with the command
     * <li>{@link HyperionEventType#SHORT_CIRCUITED} - this event measures a health problem in the past, not a problem with the current state
     * <li>{@link HyperionEventType#CANCELLED} - this event denotes a user-cancelled command.  It's not known if it would have been a success or failure, so it shouldn't count for either
     * <li>All Fallback metrics
     * <li>{@link HyperionEventType#EMIT} - this event is not a terminal state for the command
     * <li>{@link HyperionEventType#COLLAPSED} - this event is about the batching process, not the command execution
     * </ul><p>
     * 
     * @return {@link HealthCounts}
     */
    public HealthCounts getHealthCounts() {
        return healthCountsStream.getLatest();
    }

    private void unsubscribeAll() {
        healthCountsStream.unsubscribe();
        rollingCommandEventCounterStream.unsubscribe();
        cumulativeCommandEventCounterStream.unsubscribe();
        rollingCommandLatencyDistributionStream.unsubscribe();
        rollingCommandUserLatencyDistributionStream.unsubscribe();
        rollingCommandMaxConcurrencyStream.unsubscribe();
    }

    /**
     * Number of requests during rolling window.
     * Number that failed (failure + success + timeout + threadPoolRejected + semaphoreRejected).
     * Error percentage;
     */
    public static class HealthCounts {
        private final long totalCount;
        private final long errorCount;
        private final int errorPercentage;

		HealthCounts(long total, long error) {
            this.totalCount = total;
            this.errorCount = error;
            if (totalCount > 0) {
                this.errorPercentage = (int) ((double) errorCount / totalCount * 100);
            } else {
                this.errorPercentage = 0;
            }
        }

        private static final HealthCounts EMPTY = new HealthCounts(0, 0);

        public long getTotalRequests() {
            return totalCount;
        }

        public long getErrorCount() {
            return errorCount;
        }

        public int getErrorPercentage() {
            return errorPercentage;
        }

        public HealthCounts plus(long[] eventTypeCounts) {
            long updatedTotalCount = totalCount;
            long updatedErrorCount = errorCount;

            long successCount = eventTypeCounts[HyperionEventType.SUCCESS.ordinal()];
            long failureCount = eventTypeCounts[HyperionEventType.FAILURE.ordinal()];
            long timeoutCount = eventTypeCounts[HyperionEventType.TIMEOUT.ordinal()];
            long threadPoolRejectedCount = eventTypeCounts[HyperionEventType.THREAD_POOL_REJECTED.ordinal()];
            long semaphoreRejectedCount = eventTypeCounts[HyperionEventType.SEMAPHORE_REJECTED.ordinal()];

            updatedTotalCount += (successCount + failureCount + timeoutCount + threadPoolRejectedCount + semaphoreRejectedCount);
            updatedErrorCount += (failureCount + timeoutCount + threadPoolRejectedCount + semaphoreRejectedCount);
            return new HealthCounts(updatedTotalCount, updatedErrorCount);
        }

        public static HealthCounts empty() {
            return EMPTY;
        }

        public String toString() {
            return "HealthCounts[" + errorCount + " / " + totalCount + " : " + getErrorPercentage() + "%]";
        }
    }
}
