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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.pingan.testdemo.circuitbreak.HyperionCommandMetrics.HealthCounts;

//import com.netflix.hystrix.HystrixCommandMetrics;
//import com.netflix.hystrix.HystrixCommandMetrics.HealthCounts;

import rx.Subscriber;
import rx.Subscription;

/**
 * Circuit-breaker logic that is hooked into {@link HystrixCommand} execution and will stop allowing executions if failures have gone past the defined threshold.
 * <p>
 * The default (and only) implementation  will then allow a single retry after a defined sleepWindow until the execution
 * succeeds at which point it will again close the circuit and allow executions again.
 */
public interface HyperionCircuitBreaker {

    /**
     * Every {@link HystrixCommand} requests asks this if it is allowed to proceed or not.  It is idempotent and does
     * not modify any internal state, and takes into account the half-open logic which allows some requests through
     * after the circuit has been opened
     * 
     * @return boolean whether a request should be permitted
     */
    boolean allowRequest();

    /**
     * Whether the circuit is currently open (tripped).
     * 
     * @return boolean state of circuit breaker
     */
    boolean isOpen();

    /**
     * Invoked on successful executions from {@link HystrixCommand} as part of feedback mechanism when in a half-open state.
     */
    void markSuccess();

    /**
     * Invoked on unsuccessful executions from {@link HystrixCommand} as part of feedback mechanism when in a half-open state.
     */
    void markNonSuccess();

    /**
     * Invoked at start of command execution to attempt an execution.  This is non-idempotent - it may modify internal
     * state.
     */
    boolean attemptExecution();

    /**
     * @ExcludeFromJavadoc
     * @ThreadSafe
     */
    class Factory {
        // String is HystrixCommandKey.name() (we can't use HystrixCommandKey directly as we can't guarantee it implements hashcode/equals correctly)
        private static ConcurrentHashMap<String, HyperionCircuitBreaker> circuitBreakersByCommand = new ConcurrentHashMap<String, HyperionCircuitBreaker>();

        /**
         * Get the {@link HyperionCircuitBreaker} instance for a given {@link HyperionCommandKey}.
         * <p>
         * This is thread-safe and ensures only 1 {@link HyperionCircuitBreaker} per {@link HyperionCommandKey}.
         * 
         * @param key
         *            {@link HyperionCommandKey} of {@link HystrixCommand} instance requesting the {@link HyperionCircuitBreaker}
         * @param group
         *            Pass-thru to {@link HyperionCircuitBreaker}
         * @param properties
         *            Pass-thru to {@link HyperionCircuitBreaker}
         * @param metrics
         *            Pass-thru to {@link HyperionCircuitBreaker}
         * @return {@link HyperionCircuitBreaker} for {@link HyperionCommandKey}
         */
        public static HyperionCircuitBreaker getInstance(HyperionCommandKey key, HyperionCommandGroupKey group, HyperionCommandProperties properties, HyperionCommandMetrics metrics) {
            // this should find it for all but the first time
            HyperionCircuitBreaker previouslyCached = circuitBreakersByCommand.get(key.name());
            if (previouslyCached != null) {
                return previouslyCached;
            }

            // if we get here this is the first time so we need to initialize

            // Create and add to the map ... use putIfAbsent to atomically handle the possible race-condition of
            // 2 threads hitting this point at the same time and let ConcurrentHashMap provide us our thread-safety
            // If 2 threads hit here only one will get added and the other will get a non-null response instead.
            HyperionCircuitBreaker cbForCommand = circuitBreakersByCommand.putIfAbsent(key.name(), new HyperionCircuitBreakerImpl(key, group, properties, metrics));
            if (cbForCommand == null) {
                // this means the putIfAbsent step just created a new one so let's retrieve and return it
                return circuitBreakersByCommand.get(key.name());
            } else {
                // this means a race occurred and while attempting to 'put' another one got there before
                // and we instead retrieved it and will now return it
                return cbForCommand;
            }
        }

        /**
         * Get the {@link HyperionCircuitBreaker} instance for a given {@link HyperionCommandKey} or null if none exists.
         * 
         * @param key
         *            {@link HyperionCommandKey} of {@link HystrixCommand} instance requesting the {@link HyperionCircuitBreaker}
         * @return {@link HyperionCircuitBreaker} for {@link HyperionCommandKey}
         */
        public static HyperionCircuitBreaker getInstance(HyperionCommandKey key) {
            return circuitBreakersByCommand.get(key.name());
        }

        /**
         * Clears all circuit breakers. If new requests come in instances will be recreated.
         */
        /* package */static void reset() {
            circuitBreakersByCommand.clear();
        }
    }


    /**
     * The default production implementation of {@link HyperionCircuitBreaker}.
     * 
     * @ExcludeFromJavadoc
     * @ThreadSafe
     */
    /* package */class HyperionCircuitBreakerImpl implements HyperionCircuitBreaker {
        private final HyperionCommandProperties properties;
        private final HyperionCommandMetrics metrics;

        enum Status {
            CLOSED, OPEN, HALF_OPEN;
        }

        private final AtomicReference<Status> status = new AtomicReference<Status>(Status.CLOSED);
        private final AtomicLong circuitOpened = new AtomicLong(-1);
        private final AtomicReference<Subscription> activeSubscription = new AtomicReference<Subscription>(null);

        protected HyperionCircuitBreakerImpl(HyperionCommandKey key, HyperionCommandGroupKey commandGroup, final HyperionCommandProperties properties, HyperionCommandMetrics metrics) {
            this.properties = properties;
            this.metrics = metrics;

            //On a timer, this will set the circuit between OPEN/CLOSED as command executions occur
            Subscription s = subscribeToStream();
            activeSubscription.set(s);
        }

        private Subscription subscribeToStream() {
            /*
             * This stream will recalculate the OPEN/CLOSED status on every onNext from the health stream
             */
            return metrics.getHealthCountsStream()
                    .observe()
                    .subscribe(new Subscriber<HealthCounts>() {
                        @Override
                        public void onCompleted() {

                        }

                        @Override
                        public void onError(Throwable e) {

                        }

                        @Override
                        public void onNext(HealthCounts hc) {
                        	System.out.println(Thread.currentThread().getName() + ": in onNext!!!!----" + hc.toString() 
                        		+ ", status=" + status.get() 
                        		+ ", circuitTime=" + circuitOpened.get() 
                        		+ ", " + System.currentTimeMillis());
                            // check if we are past the statisticalWindowVolumeThreshold
                            if (hc.getTotalRequests() < properties.circuitBreakerRequestVolumeThreshold().get()) {
                                // we are not past the minimum volume threshold for the stat window,
                                // so no change to circuit status.
                                // if it was CLOSED, it stays CLOSED
                                // if it was half-open, we need to wait for a successful command execution
                                // if it was open, we need to wait for sleep window to elapse
                            } else {
                                if (hc.getErrorPercentage() < properties.circuitBreakerErrorThresholdPercentage().get()) {
                                    //we are not past the minimum error threshold for the stat window,
                                    // so no change to circuit status.
                                    // if it was CLOSED, it stays CLOSED
                                    // if it was half-open, we need to wait for a successful command execution
                                    // if it was open, we need to wait for sleep window to elapse
                                } else {
                                    // our failure rate is too high, we need to set the state to OPEN
                                    if (status.compareAndSet(Status.CLOSED, Status.OPEN)) {
                                    	System.out.println("subscribeToStream: close -> open成功" + ", " + System.currentTimeMillis());
                                        circuitOpened.set(System.currentTimeMillis());
                                    }
                                }
                            }
                        }
                    });
        }

        @Override
        public void markSuccess() {
            if (status.compareAndSet(Status.HALF_OPEN, Status.CLOSED)) {
            	System.out.println("markSuccess: half_open -> close成功" + ", " + System.currentTimeMillis());
                //This thread wins the race to close the circuit - it resets the stream to start it over from 0
                metrics.resetStream();
                Subscription previousSubscription = activeSubscription.get();
                if (previousSubscription != null) {
                    previousSubscription.unsubscribe();
                }
                Subscription newSubscription = subscribeToStream();
                activeSubscription.set(newSubscription);
                circuitOpened.set(-1L);
            }
        }

        @Override
        public void markNonSuccess() {
            if (status.compareAndSet(Status.HALF_OPEN, Status.OPEN)) {
            	System.out.println("markNonSuccess: half_open -> open成功" + ", " + System.currentTimeMillis());
                //This thread wins the race to re-open the circuit - it resets the start time for the sleep window
                circuitOpened.set(System.currentTimeMillis());
            }
        }

        @Override
        public boolean isOpen() {
            if (properties.circuitBreakerForceOpen().get()) {
                return true;
            }
            if (properties.circuitBreakerForceClosed().get()) {
                return false;
            }
            return circuitOpened.get() >= 0;
        }

        @Override
        public boolean allowRequest() {
            if (properties.circuitBreakerForceOpen().get()) {
                return false;
            }
            if (properties.circuitBreakerForceClosed().get()) {
                return true;
            }
            if (circuitOpened.get() == -1) {
                return true;
            } else {
                if (status.get().equals(Status.HALF_OPEN)) {
                    return false;
                } else {
                    return isAfterSleepWindow();
                }
            }
        }

        private boolean isAfterSleepWindow() {
            final long circuitOpenTime = circuitOpened.get();
            final long currentTime = System.currentTimeMillis();
            final long sleepWindowTime = properties.circuitBreakerSleepWindowInMilliseconds().get();
//        	System.out.println("isAfterSleepWindow: status=" + status.get() + ", currentTime=" + currentTime
//        			+ ", circuitOpenTime=" + circuitOpenTime 
//        			+ ", sleepWindowTime=" + sleepWindowTime
//        			+ ", result=" + (currentTime > circuitOpenTime + sleepWindowTime)
//        			+ ", " + System.currentTimeMillis());
            return currentTime > circuitOpenTime + sleepWindowTime;
        }

        @Override
        public boolean attemptExecution() {
//        	System.out.println("attemptExecution," + System.currentTimeMillis());
            if (properties.circuitBreakerForceOpen().get()) {
                return false;
            }
            if (properties.circuitBreakerForceClosed().get()) {
                return true;
            }
            if (circuitOpened.get() == -1) {
                return true;
            } else {
                if (isAfterSleepWindow()) {
                    //only the first request after sleep window should execute
                    //if the executing command succeeds, the status will transition to CLOSED
                    //if the executing command fails, the status will transition to OPEN
                    //if the executing command gets unsubscribed, the status will transition to OPEN
                    if (status.compareAndSet(Status.OPEN, Status.HALF_OPEN)) {
                    	System.out.println("attemptExecution: open -> half_open成功" + ", " + System.currentTimeMillis());
                        return true;
                    } else {
                    	System.out.println("attemptExecution: open -> half_open失败" + ", " + System.currentTimeMillis());
                        return false;
                    }
                } else {
                    return false;
                }
            }
        }
    }

    /**
     * An implementation of the circuit breaker that does nothing.
     * 
     * @ExcludeFromJavadoc
     */
    /* package */static class NoOpCircuitBreaker implements HyperionCircuitBreaker {

        @Override
        public boolean allowRequest() {
            return true;
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public void markSuccess() {

        }

        @Override
        public void markNonSuccess() {

        }

        @Override
        public boolean attemptExecution() {
            return true;
        }
    }

}
