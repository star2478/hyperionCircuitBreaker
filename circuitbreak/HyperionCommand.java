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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.netflix.hystrix.util.Exceptions;
import rx.Observable;
import rx.functions.Action0;

//import com.netflix.hystrix.HystrixThreadPool;
//import com.netflix.hystrix.HystrixThreadPoolKey;
//import com.netflix.hystrix.HystrixThreadPoolProperties;
import com.netflix.hystrix.exception.HystrixBadRequestException;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.netflix.hystrix.exception.HystrixRuntimeException.FailureType;
import com.netflix.hystrix.strategy.executionhook.HystrixCommandExecutionHook;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesStrategy;
import rx.functions.Func0;

/**
 * Used to wrap code that will execute potentially risky functionality (typically meaning a service call over the network)
 * with fault and latency tolerance, statistics and performance metrics capture, circuit breaker and bulkhead functionality.
 * This command is essentially a blocking command but provides an Observable facade if used with observe()
 * 
 * @param <R>
 *            the return type
 * 
 * @ThreadSafe
 */
public abstract class HyperionCommand<R> extends AbstractCommand<R> implements HyperionExecutable<R>, HyperionInvokableInfo<R>, HyperionObservable<R> {


    /**
     * Construct a {@link HyperionCommand} with defined {@link HyperionCommandGroupKey}.
     * <p>
     * The {@link HyperionCommandKey} will be derived from the implementing class name.
     * 
     * @param group
     *            {@link HyperionCommandGroupKey} used to group together multiple {@link HyperionCommand} objects.
     *            <p>
     *            The {@link HyperionCommandGroupKey} is used to represent a common relationship between commands. For example, a library or team name, the system all related commands interact with,
     *            common business purpose etc.
     */
    protected HyperionCommand(HyperionCommandGroupKey group) {
        super(group, null, null, null, null, null, null, null, null, null, null, null);
    }

//
//    /**
//     * Construct a {@link HyperionCommand} with defined {@link HyperionCommandGroupKey} and {@link HystrixThreadPoolKey}.
//     * <p>
//     * The {@link HyperionCommandKey} will be derived from the implementing class name.
//     *
//     * @param group
//     *            {@link HyperionCommandGroupKey} used to group together multiple {@link HyperionCommand} objects.
//     *            <p>
//     *            The {@link HyperionCommandGroupKey} is used to represent a common relationship between commands. For example, a library or team name, the system all related commands interact with,
//     *            common business purpose etc.
//     * @param threadPool
//     *            {@link HystrixThreadPoolKey} used to identify the thread pool in which a {@link HyperionCommand} executes.
//     */
//    protected HyperionCommand(HyperionCommandGroupKey group, HystrixThreadPoolKey threadPool) {
//        super(group, null, threadPool, null, null, null, null, null, null, null, null, null);
//    }
//
//    /**
//     * Construct a {@link HyperionCommand} with defined {@link HyperionCommandGroupKey} and thread timeout
//     * <p>
//     * The {@link HyperionCommandKey} will be derived from the implementing class name.
//     *
//     * @param group
//     *            {@link HyperionCommandGroupKey} used to group together multiple {@link HyperionCommand} objects.
//     *            <p>
//     *            The {@link HyperionCommandGroupKey} is used to represent a common relationship between commands. For example, a library or team name, the system all related commands interact with,
//     *            common business purpose etc.
//     * @param executionIsolationThreadTimeoutInMilliseconds
//     *            Time in milliseconds at which point the calling thread will timeout (using {@link Future#get}) and walk away from the executing thread.
//     */
//    protected HyperionCommand(HyperionCommandGroupKey group, int executionIsolationThreadTimeoutInMilliseconds) {
//        super(group, null, null, null, null, HyperionCommandProperties.Setter().withExecutionTimeoutInMilliseconds(executionIsolationThreadTimeoutInMilliseconds), null, null, null, null, null, null);
//    }
//
//    /**
//     * Construct a {@link HyperionCommand} with defined {@link HyperionCommandGroupKey}, {@link HystrixThreadPoolKey}, and thread timeout.
//     * <p>
//     * The {@link HyperionCommandKey} will be derived from the implementing class name.
//     *
//     * @param group
//     *            {@link HyperionCommandGroupKey} used to group together multiple {@link HyperionCommand} objects.
//     *            <p>
//     *            The {@link HyperionCommandGroupKey} is used to represent a common relationship between commands. For example, a library or team name, the system all related commands interact with,
//     *            common business purpose etc.
//     * @param threadPool
//     *            {@link HystrixThreadPool} used to identify the thread pool in which a {@link HyperionCommand} executes.
//     * @param executionIsolationThreadTimeoutInMilliseconds
//     *            Time in milliseconds at which point the calling thread will timeout (using {@link Future#get}) and walk away from the executing thread.
//     */
//    protected HyperionCommand(HyperionCommandGroupKey group, HystrixThreadPoolKey threadPool, int executionIsolationThreadTimeoutInMilliseconds) {
//        super(group, null, threadPool, null, null, HyperionCommandProperties.Setter().withExecutionTimeoutInMilliseconds(executionIsolationThreadTimeoutInMilliseconds), null, null, null, null, null, null);
//    }

    /**
     * Construct a {@link HyperionCommand} with defined {@link Setter} that allows injecting property and strategy overrides and other optional arguments.
     * <p>
     * NOTE: The {@link HyperionCommandKey} is used to associate a {@link HyperionCommand} with {@link HyperionCircuitBreaker}, {@link HyperionCommandMetrics} and other objects.
     * <p>
     * Do not create multiple {@link HyperionCommand} implementations with the same {@link HyperionCommandKey} but different injected default properties as the first instantiated will win.
     * <p>
     * Properties passed in via {@link Setter#andCommandPropertiesDefaults} or {@link Setter#andThreadPoolPropertiesDefaults} are cached for the given {@link HyperionCommandKey} for the life of the JVM
     * or until {@link Hyperion#reset()} is called. Dynamic properties allow runtime changes. Read more on the <a href="https://github.com/Netflix/Hystrix/wiki/Configuration">Hystrix Wiki</a>.
     * 
     * @param setter
     *            Fluent interface for constructor arguments
     */
    protected HyperionCommand(Setter setter) {
        // use 'null' to specify use the default
        this(setter.groupKey, setter.commandKey, setter.threadPoolKey, null, null, setter.commandPropertiesDefaults, setter.threadPoolPropertiesDefaults, null, null, null, null, null);
    }

    /**
     * Allow constructing a {@link HyperionCommand} with injection of most aspects of its functionality.
     * <p>
     * Some of these never have a legitimate reason for injection except in unit testing.
     * <p>
     * Most of the args will revert to a valid default if 'null' is passed in.
     */
    /* package for testing */HyperionCommand(HyperionCommandGroupKey group, HyperionCommandKey key, HyperionThreadPoolKey threadPoolKey, HyperionCircuitBreaker circuitBreaker, HyperionThreadPool threadPool,
            HyperionCommandProperties.Setter commandPropertiesDefaults, HyperionThreadPoolProperties.Setter threadPoolPropertiesDefaults,
            HyperionCommandMetrics metrics, TryableSemaphore fallbackSemaphore, TryableSemaphore executionSemaphore,
            HystrixPropertiesStrategy propertiesStrategy, HystrixCommandExecutionHook executionHook) {
        super(group, key, threadPoolKey, circuitBreaker, threadPool, commandPropertiesDefaults, threadPoolPropertiesDefaults, metrics, fallbackSemaphore, executionSemaphore, propertiesStrategy, executionHook);
    }

    /**
     * Fluent interface for arguments to the {@link HyperionCommand} constructor.
     * <p>
     * The required arguments are set via the 'with' factory method and optional arguments via the 'and' chained methods.
     * <p>
     * Example:
     * <pre> {@code
     *  Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("GroupName"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("CommandName"));
     * } </pre>
     * 
     * @NotThreadSafe
     */
    final public static class Setter {

        protected final HyperionCommandGroupKey groupKey;
        protected HyperionCommandKey commandKey;
        protected HyperionThreadPoolKey threadPoolKey;
        protected HyperionCommandProperties.Setter commandPropertiesDefaults;
        protected HyperionThreadPoolProperties.Setter threadPoolPropertiesDefaults;

        /**
         * Setter factory method containing required values.
         * <p>
         * All optional arguments can be set via the chained methods.
         * 
         * @param groupKey
         *            {@link HyperionCommandGroupKey} used to group together multiple {@link HyperionCommand} objects.
         *            <p>
         *            The {@link HyperionCommandGroupKey} is used to represent a common relationship between commands. For example, a library or team name, the system all related commands interace
         *            with,
         *            common business purpose etc.
         */
        protected Setter(HyperionCommandGroupKey groupKey) {
            this.groupKey = groupKey;
        }

        /**
         * Setter factory method with required values.
         * <p>
         * All optional arguments can be set via the chained methods.
         * 
         * @param groupKey
         *            {@link HyperionCommandGroupKey} used to group together multiple {@link HyperionCommand} objects.
         *            <p>
         *            The {@link HyperionCommandGroupKey} is used to represent a common relationship between commands. For example, a library or team name, the system all related commands interace
         *            with,
         *            common business purpose etc.
         */
        public static Setter withGroupKey(HyperionCommandGroupKey groupKey) {
            return new Setter(groupKey);
        }

        /**
         * @param commandKey
         *            {@link HyperionCommandKey} used to identify a {@link HyperionCommand} instance for statistics, circuit-breaker, properties, etc.
         *            <p>
         *            By default this will be derived from the instance class name.
         *            <p>
         *            NOTE: Every unique {@link HyperionCommandKey} will result in new instances of {@link HyperionCircuitBreaker}, {@link HyperionCommandMetrics} and {@link HyperionCommandProperties}.
         *            Thus,
         *            the number of variants should be kept to a finite and reasonable number to avoid high-memory usage or memory leaks.
         *            <p>
         *            Hundreds of keys is fine, tens of thousands is probably not.
         * @return Setter for fluent interface via method chaining
         */
        public Setter andCommandKey(HyperionCommandKey commandKey) {
            this.commandKey = commandKey;
            return this;
        }

        /**
         * @param threadPoolKey
         *            {@link HyperionThreadPoolKey} used to define which thread-pool this command should run in (when configured to run on separate threads via
         *            {@link HyperionCommandProperties#executionIsolationStrategy()}).
         *            <p>
         *            By default this is derived from the {@link HyperionCommandGroupKey} but if injected this allows multiple commands to have the same {@link HyperionCommandGroupKey} but different
         *            thread-pools.
         * @return Setter for fluent interface via method chaining
         */
        public Setter andThreadPoolKey(HyperionThreadPoolKey threadPoolKey) {
            this.threadPoolKey = threadPoolKey;
            return this;
        }

        /**
         * Optional
         * 
         * @param commandPropertiesDefaults
         *            {@link HyperionCommandProperties.Setter} with property overrides for this specific instance of {@link HyperionCommand}.
         *            <p>
         *            See the {@link HystrixPropertiesStrategy} JavaDocs for more information on properties and order of precedence.
         * @return Setter for fluent interface via method chaining
         */
        public Setter andCommandPropertiesDefaults(HyperionCommandProperties.Setter commandPropertiesDefaults) {
            this.commandPropertiesDefaults = commandPropertiesDefaults;
            return this;
        }

        /**
         * Optional
         * 
         * @param threadPoolPropertiesDefaults
         *            {@link HyperionThreadPoolProperties.Setter} with property overrides for the {@link HyperionThreadPool} used by this specific instance of {@link HyperionCommand}.
         *            <p>
         *            See the {@link HystrixPropertiesStrategy} JavaDocs for more information on properties and order of precedence.
         * @return Setter for fluent interface via method chaining
         */
        public Setter andThreadPoolPropertiesDefaults(HyperionThreadPoolProperties.Setter threadPoolPropertiesDefaults) {
            this.threadPoolPropertiesDefaults = threadPoolPropertiesDefaults;
            return this;
        }

    }

	private final AtomicReference<Thread> executionThread = new AtomicReference<Thread>();
	private final AtomicBoolean interruptOnFutureCancel = new AtomicBoolean(false);

	/**
     * Implement this method with code to be executed when {@link #execute()} or {@link #queue()} are invoked.
     * 
     * @return R response type
     * @throws Exception
     *             if command execution fails
     */
    protected abstract R run() throws Exception;

    /**
     * If {@link #execute()} or {@link #queue()} fails in any way then this method will be invoked to provide an opportunity to return a fallback response.
     * <p>
     * This should do work that does not require network transport to produce.
     * <p>
     * In other words, this should be a static or cached result that can immediately be returned upon failure.
     * <p>
     * If network traffic is wanted for fallback (such as going to MemCache) then the fallback implementation should invoke another {@link HyperionCommand} instance that protects against that network
     * access and possibly has another level of fallback that does not involve network access.
     * <p>
     * DEFAULT BEHAVIOR: It throws UnsupportedOperationException.
     * 
     * @return R or throw UnsupportedOperationException if not implemented
     */
    protected R getFallback() {
        throw new UnsupportedOperationException("No fallback available.");
    }

    @Override
    final protected Observable<R> getExecutionObservable() {
        return Observable.defer(new Func0<Observable<R>>() {
            @Override
            public Observable<R> call() {
                try {
                    return Observable.just(run());
                } catch (Throwable ex) {
//                	System.out.println("dsssssssssssssssssssssssssssssssssssss");
                    return Observable.error(ex);
                }
            }
        }).doOnSubscribe(new Action0() {
            @Override
            public void call() {
                // Save thread on which we get subscribed so that we can interrupt it later if needed
                executionThread.set(Thread.currentThread());
            }
        });
    }

    @Override
    final protected Observable<R> getFallbackObservable() {
        return Observable.defer(new Func0<Observable<R>>() {
            @Override
            public Observable<R> call() {
                try {
                    return Observable.just(getFallback());
                } catch (Throwable ex) {
                    return Observable.error(ex);
                }
            }
        });
    }

    /**
     * Used for synchronous execution of command.
     * 
     * @return R
     *         Result of {@link #run()} execution or a fallback from {@link #getFallback()} if the command fails for any reason.
     * @throws HystrixRuntimeException
     *             if a failure occurs and a fallback cannot be retrieved
     * @throws HystrixBadRequestException
     *             if invalid arguments or state were used representing a user failure, not a system failure
     * @throws IllegalStateException
     *             if invoked more than once
     */
    public R execute() {
        try {
            return queue().get();
        } catch (Exception e) {
        	System.out.println("in catch exp of execute()");
//        	e.printStackTrace();
            throw Exceptions.sneakyThrow(decomposeException(e));
        }
    }

    /**
     * Used for asynchronous execution of command.
     * <p>
     * This will queue up the command on the thread pool and return an {@link Future} to get the result once it completes.
     * <p>
     * NOTE: If configured to not run in a separate thread, this will have the same effect as {@link #execute()} and will block.
     * <p>
     * We don't throw an exception but just flip to synchronous execution so code doesn't need to change in order to switch a command from running on a separate thread to the calling thread.
     * 
     * @return {@code Future<R>} Result of {@link #run()} execution or a fallback from {@link #getFallback()} if the command fails for any reason.
     * @throws HystrixRuntimeException
     *             if a fallback does not exist
     *             <p>
     *             <ul>
     *             <li>via {@code Future.get()} in {@link ExecutionException#getCause()} if a failure occurs</li>
     *             <li>or immediately if the command can not be queued (such as short-circuited, thread-pool/semaphore rejected)</li>
     *             </ul>
     * @throws HystrixBadRequestException
     *             via {@code Future.get()} in {@link ExecutionException#getCause()} if invalid arguments or state were used representing a user failure, not a system failure
     * @throws IllegalStateException
     *             if invoked more than once
     */
    public Future<R> queue() {
        /*
         * The Future returned by Observable.toBlocking().toFuture() does not implement the
         * interruption of the execution thread when the "mayInterrupt" flag of Future.cancel(boolean) is set to true;
         * thus, to comply with the contract of Future, we must wrap around it.
         */
//    	System.out.println("begin queue()--------------------");
        final Future<R> delegate = toObservable().toBlocking().toFuture();

        final Future<R> f = new Future<R>() {

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (delegate.isCancelled()) {
                    return false;
                }

                if (HyperionCommand.this.getProperties().executionIsolationThreadInterruptOnFutureCancel().get()) {
                    /*
                     * The only valid transition here is false -> true. If there are two futures, say f1 and f2, created by this command
                     * (which is super-weird, but has never been prohibited), and calls to f1.cancel(true) and to f2.cancel(false) are
                     * issued by different threads, it's unclear about what value would be used by the time mayInterruptOnCancel is checked.
                     * The most consistent way to deal with this scenario is to say that if *any* cancellation is invoked with interruption,
                     * than that interruption request cannot be taken back.
                     */
                    interruptOnFutureCancel.compareAndSet(false, mayInterruptIfRunning);
        		}

                final boolean res = delegate.cancel(interruptOnFutureCancel.get());

                if (!isExecutionComplete() && interruptOnFutureCancel.get()) {
                    final Thread t = executionThread.get();
                    if (t != null && !t.equals(Thread.currentThread())) {
                        t.interrupt();
                    }
                }

                return res;
			}

            @Override
            public boolean isCancelled() {
                return delegate.isCancelled();
			}

            @Override
            public boolean isDone() {
                return delegate.isDone();
			}

            @Override
            public R get() throws InterruptedException, ExecutionException {
                return delegate.get();
            }

            @Override
            public R get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                return delegate.get(timeout, unit);
            }
        	
        };

        /* special handling of error states that throw immediately */
        if (f.isDone()) {
            try {
//            	System.out.println(Thread.currentThread().getName()+": before f.get()");
                f.get();
//            	System.out.println(Thread.currentThread().getName()+": after f.get()");
                return f;
            } catch (Exception e) {
            	System.out.println("in catch expr of queue()");
//            	e.printStackTrace();
            	if(true) {
            		return f;
            	}
//                Throwable t = decomposeException(e);
//                if (t instanceof HystrixBadRequestException) {
//                    return f;
//                } else if (t instanceof HystrixRuntimeException) {
//                    HystrixRuntimeException hre = (HystrixRuntimeException) t;
//                    switch (hre.getFailureType()) {
//					case COMMAND_EXCEPTION:
//					case TIMEOUT:
//						// we don't throw these types from queue() only from queue().get() as they are execution errors
//						return f;
//					default:
//						// these are errors we throw from queue() as they as rejection type errors
//						throw hre;
//					}
//                } else {
//                    throw Exceptions.sneakyThrow(t);
//                }
            }
        }

        return f;
    }

    @Override
    protected String getFallbackMethodName() {
        return "getFallback";
    }

    @Override
    protected boolean isFallbackUserDefined() {
        Boolean containsFromMap = commandContainsFallback.get(commandKey);
        if (containsFromMap != null) {
            return containsFromMap;
        } else {
            Boolean toInsertIntoMap;
            try {
                getClass().getDeclaredMethod("getFallback");
                toInsertIntoMap = true;
            } catch (NoSuchMethodException nsme) {
                toInsertIntoMap = false;
            }
            commandContainsFallback.put(commandKey, toInsertIntoMap);
            return toInsertIntoMap;
        }
    }

    @Override
    protected boolean commandIsScalar() {
        return true;
    }
}
