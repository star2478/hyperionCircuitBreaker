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

import com.pingan.testdemo.circuitbreak.metric.HyperionCommandCompletion;
import rx.functions.Func0;
import rx.functions.Func2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Used by {@link HyperionThreadPool} to record metrics.
 */
public class HyperionThreadPoolMetrics extends HyperionMetrics {

    private static final HyperionEventType[] ALL_COMMAND_EVENT_TYPES = HyperionEventType.values();
    private static final HyperionEventType.ThreadPool[] ALL_THREADPOOL_EVENT_TYPES = HyperionEventType.ThreadPool.values();
    private static final int NUMBER_THREADPOOL_EVENT_TYPES = ALL_THREADPOOL_EVENT_TYPES.length;

    // String is HystrixThreadPoolKey.name() (we can't use HystrixThreadPoolKey directly as we can't guarantee it implements hashcode/equals correctly)
    private static final ConcurrentHashMap<String, HyperionThreadPoolMetrics> metrics = new ConcurrentHashMap<String, HyperionThreadPoolMetrics>();

    /**
     * Get or create the {@link HyperionThreadPoolMetrics} instance for a given {@link HyperionThreadPoolKey}.
     * <p>
     * This is thread-safe and ensures only 1 {@link HyperionThreadPoolMetrics} per {@link HyperionThreadPoolKey}.
     * 
     * @param key
     *            {@link HyperionThreadPoolKey} of {@link HyperionThreadPool} instance requesting the {@link HyperionThreadPoolMetrics}
     * @param threadPool
     *            Pass-thru of ThreadPoolExecutor to {@link HyperionThreadPoolMetrics} instance on first time when constructed
     * @param properties
     *            Pass-thru to {@link HyperionThreadPoolMetrics} instance on first time when constructed
     * @return {@link HyperionThreadPoolMetrics}
     */
    public static HyperionThreadPoolMetrics getInstance(HyperionThreadPoolKey key, ThreadPoolExecutor threadPool, HyperionThreadPoolProperties properties) {
        // attempt to retrieve from cache first
        HyperionThreadPoolMetrics threadPoolMetrics = metrics.get(key.name());
        if (threadPoolMetrics != null) {
            return threadPoolMetrics;
        } else {
            synchronized (HyperionThreadPoolMetrics.class) {
                HyperionThreadPoolMetrics existingMetrics = metrics.get(key.name());
                if (existingMetrics != null) {
                    return existingMetrics;
                } else {
                    HyperionThreadPoolMetrics newThreadPoolMetrics = new HyperionThreadPoolMetrics(key, threadPool, properties);
                    metrics.putIfAbsent(key.name(), newThreadPoolMetrics);
                    return newThreadPoolMetrics;
                }
            }
        }
    }

    /**
     * Get the {@link HyperionThreadPoolMetrics} instance for a given {@link HyperionThreadPoolKey} or null if one does not exist.
     * 
     * @param key
     *            {@link HyperionThreadPoolKey} of {@link HyperionThreadPool} instance requesting the {@link HyperionThreadPoolMetrics}
     * @return {@link HyperionThreadPoolMetrics}
     */
    public static HyperionThreadPoolMetrics getInstance(HyperionThreadPoolKey key) {
        return metrics.get(key.name());
    }

    /**
     * All registered instances of {@link HyperionThreadPoolMetrics}
     * 
     * @return {@code Collection<HystrixThreadPoolMetrics>}
     */
    public static Collection<HyperionThreadPoolMetrics> getInstances() {
        List<HyperionThreadPoolMetrics> threadPoolMetrics = new ArrayList<HyperionThreadPoolMetrics>();
        for (HyperionThreadPoolMetrics tpm: metrics.values()) {
            if (hasExecutedCommandsOnThread(tpm)) {
                threadPoolMetrics.add(tpm);
            }
        }

        return Collections.unmodifiableCollection(threadPoolMetrics);
    }

    private static boolean hasExecutedCommandsOnThread(HyperionThreadPoolMetrics threadPoolMetrics) {
        return threadPoolMetrics.getCurrentCompletedTaskCount().intValue() > 0;
    }

    public static final Func2<long[], HyperionCommandCompletion, long[]> appendEventToBucket
            = new Func2<long[], HyperionCommandCompletion, long[]>() {
        @Override
        public long[] call(long[] initialCountArray, HyperionCommandCompletion execution) {
            ExecutionResult.EventCounts eventCounts = execution.getEventCounts();
            for (HyperionEventType eventType: ALL_COMMAND_EVENT_TYPES) {
                long eventCount = eventCounts.getCount(eventType);
                HyperionEventType.ThreadPool threadPoolEventType = HyperionEventType.ThreadPool.from(eventType);
                if (threadPoolEventType != null) {
                    initialCountArray[threadPoolEventType.ordinal()] += eventCount;
                }
            }
            return initialCountArray;
        }
    };

    public static final Func2<long[], long[], long[]> counterAggregator = new Func2<long[], long[], long[]>() {
        @Override
        public long[] call(long[] cumulativeEvents, long[] bucketEventCounts) {
            for (int i = 0; i < NUMBER_THREADPOOL_EVENT_TYPES; i++) {
                cumulativeEvents[i] += bucketEventCounts[i];
            }
            return cumulativeEvents;
        }
    };

    /**
     * Clears all state from metrics. If new requests come in instances will be recreated and metrics started from scratch.
     *
     */
    /* package */ static void reset() {
        metrics.clear();
    }

    private final HyperionThreadPoolKey threadPoolKey;
    private final ThreadPoolExecutor threadPool;
    private final HyperionThreadPoolProperties properties;

    private final AtomicInteger concurrentExecutionCount = new AtomicInteger();

//    private final RollingThreadPoolEventCounterStream rollingCounterStream;
//    private final CumulativeThreadPoolEventCounterStream cumulativeCounterStream;
//    private final RollingThreadPoolMaxConcurrencyStream rollingThreadPoolMaxConcurrencyStream;

    private HyperionThreadPoolMetrics(HyperionThreadPoolKey threadPoolKey, ThreadPoolExecutor threadPool, HyperionThreadPoolProperties properties) {
        super(null);
        this.threadPoolKey = threadPoolKey;
        this.threadPool = threadPool;
        this.properties = properties;

//        rollingCounterStream = RollingThreadPoolEventCounterStream.getInstance(threadPoolKey, properties);
//        cumulativeCounterStream = CumulativeThreadPoolEventCounterStream.getInstance(threadPoolKey, properties);
//        rollingThreadPoolMaxConcurrencyStream = RollingThreadPoolMaxConcurrencyStream.getInstance(threadPoolKey, properties);
    }

    /**
     * {@link ThreadPoolExecutor} this executor represents.
     *
     * @return ThreadPoolExecutor
     */
    public ThreadPoolExecutor getThreadPool() {
        return threadPool;
    }

    /**
     * {@link HyperionThreadPoolKey} these metrics represent.
     * 
     * @return HystrixThreadPoolKey
     */
    public HyperionThreadPoolKey getThreadPoolKey() {
        return threadPoolKey;
    }

    /**
     * {@link HyperionThreadPoolProperties} of the {@link HyperionThreadPool} these metrics represent.
     * 
     * @return HystrixThreadPoolProperties
     */
    public HyperionThreadPoolProperties getProperties() {
        return properties;
    }

    /**
     * Value from {@link ThreadPoolExecutor#getActiveCount()}
     * 
     * @return Number
     */
    public Number getCurrentActiveCount() {
        return threadPool.getActiveCount();
    }

    /**
     * Value from {@link ThreadPoolExecutor#getCompletedTaskCount()}
     * 
     * @return Number
     */
    public Number getCurrentCompletedTaskCount() {
        return threadPool.getCompletedTaskCount();
    }

    /**
     * Value from {@link ThreadPoolExecutor#getCorePoolSize()}
     * 
     * @return Number
     */
    public Number getCurrentCorePoolSize() {
        return threadPool.getCorePoolSize();
    }

    /**
     * Value from {@link ThreadPoolExecutor#getLargestPoolSize()}
     * 
     * @return Number
     */
    public Number getCurrentLargestPoolSize() {
        return threadPool.getLargestPoolSize();
    }

    /**
     * Value from {@link ThreadPoolExecutor#getMaximumPoolSize()}
     * 
     * @return Number
     */
    public Number getCurrentMaximumPoolSize() {
        return threadPool.getMaximumPoolSize();
    }

    /**
     * Value from {@link ThreadPoolExecutor#getPoolSize()}
     * 
     * @return Number
     */
    public Number getCurrentPoolSize() {
        return threadPool.getPoolSize();
    }

    /**
     * Value from {@link ThreadPoolExecutor#getTaskCount()}
     * 
     * @return Number
     */
    public Number getCurrentTaskCount() {
        return threadPool.getTaskCount();
    }

    /**
     * Current size of {@link BlockingQueue} used by the thread-pool
     * 
     * @return Number
     */
    public Number getCurrentQueueSize() {
        return threadPool.getQueue().size();
    }

    /**
     * Invoked each time a thread is executed.
     */
    public void markThreadExecution() {
        concurrentExecutionCount.incrementAndGet();
    }

    /**
     * Rolling count of number of threads executed during rolling statistical window.
     * <p>
     * The rolling window is defined by {@link HystrixThreadPoolProperties#metricsRollingStatisticalWindowInMilliseconds()}.
     *
     * @return rolling count of threads executed
     */
//    public long getRollingCountThreadsExecuted() {
//        return rollingCounterStream.getLatestCount(HyperionEventType.ThreadPool.EXECUTED);
//    }

    /**
     * Cumulative count of number of threads executed since the start of the application.
     * 
     * @return cumulative count of threads executed
     */
//    public long getCumulativeCountThreadsExecuted() {
//        return cumulativeCounterStream.getLatestCount(HyperionEventType.ThreadPool.EXECUTED);
//    }

    /**
     * Rolling count of number of threads rejected during rolling statistical window.
     * <p>
     * The rolling window is defined by {@link HystrixThreadPoolProperties#metricsRollingStatisticalWindowInMilliseconds()}.
     *
     * @return rolling count of threads rejected
     */
//    public long getRollingCountThreadsRejected() {
//        return rollingCounterStream.getLatestCount(HyperionEventType.ThreadPool.REJECTED);
//    }

    /**
     * Cumulative count of number of threads rejected since the start of the application.
     *
     * @return cumulative count of threads rejected
     */
//    public long getCumulativeCountThreadsRejected() {
//        return cumulativeCounterStream.getLatestCount(HyperionEventType.ThreadPool.REJECTED);
//    }
//
//    public long getRollingCount(HyperionEventType.ThreadPool event) {
//        return rollingCounterStream.getLatestCount(event);
//    }
//
//    public long getCumulativeCount(HyperionEventType.ThreadPool event) {
//        return cumulativeCounterStream.getLatestCount(event);
//    }

//    @Override
//	public long getCumulativeCount(HyperionRollingNumberEvent event) {
//        return cumulativeCounterStream.getLatestCount(HyperionEventType.ThreadPool.from(event));
//    }

//    @Override
//    public long getRollingCount(HyperionRollingNumberEvent event) {
//        return rollingCounterStream.getLatestCount(HyperionEventType.ThreadPool.from(event));
//    }

    /**
     * Invoked each time a thread completes.
     */
    public void markThreadCompletion() {
        concurrentExecutionCount.decrementAndGet();
    }

    /**
     * Rolling max number of active threads during rolling statistical window.
     * <p>
     * The rolling window is defined by {@link HystrixThreadPoolProperties#metricsRollingStatisticalWindowInMilliseconds()}.
     * 
     * @return rolling max active threads
     */
//    public long getRollingMaxActiveThreads() {
//        return rollingThreadPoolMaxConcurrencyStream.getLatestRollingMax();
//    }

    /**
     * Invoked each time a command is rejected from the thread-pool
     */
    public void markThreadRejection() {
        concurrentExecutionCount.decrementAndGet();
    }

    public static Func0<Integer> getCurrentConcurrencyThunk(final HyperionThreadPoolKey threadPoolKey) {
        return new Func0<Integer>() {
            @Override
            public Integer call() {
                return HyperionThreadPoolMetrics.getInstance(threadPoolKey).concurrentExecutionCount.get();
            }
        };
    }
}