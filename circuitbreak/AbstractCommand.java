/**
 * Copyright 2013 Netflix, Inc.
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

import com.pingan.testdemo.circuitbreak.HyperionCircuitBreaker;
import com.pingan.testdemo.circuitbreak.HyperionCircuitBreaker.NoOpCircuitBreaker;
import com.pingan.testdemo.circuitbreak.HyperionCommandProperties.ExecutionIsolationStrategy;
import com.pingan.testdemo.circuitbreak.exception.ExceptionNotWrappedByHyperion;
import com.pingan.testdemo.circuitbreak.exception.HyperionBadRequestException;
import com.pingan.testdemo.circuitbreak.exception.HyperionRuntimeException;
import com.pingan.testdemo.circuitbreak.exception.HyperionRuntimeException.FailureType;
import com.pingan.testdemo.circuitbreak.exception.HyperionTimeoutException;
import com.pingan.testdemo.circuitbreak.strategy.properties.HyperionPropertiesFactory;
import com.netflix.hystrix.strategy.concurrency.HystrixRequestContext;
import com.netflix.hystrix.strategy.eventnotifier.HystrixEventNotifier;
import com.netflix.hystrix.strategy.executionhook.HystrixCommandExecutionHook;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesStrategy;
import com.netflix.hystrix.strategy.properties.HystrixProperty;
import com.netflix.hystrix.util.HystrixTimer.TimerListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Notification;
import rx.Observable;
import rx.Observable.Operator;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subjects.ReplaySubject;
import rx.subscriptions.CompositeSubscription;

import java.lang.ref.Reference;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/* package */abstract class AbstractCommand<R> implements HyperionInvokableInfo<R>, HyperionObservable<R> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractCommand.class);
    protected final HyperionCircuitBreaker circuitBreaker;
    protected final HyperionThreadPool threadPool;
    protected final HyperionThreadPoolKey threadPoolKey;
    protected final HyperionCommandProperties properties;

    protected enum TimedOutStatus {
        NOT_EXECUTED, COMPLETED, TIMED_OUT
    }

    protected enum CommandState {
        NOT_STARTED, OBSERVABLE_CHAIN_CREATED, USER_CODE_EXECUTED, UNSUBSCRIBED, TERMINAL
    }

    protected enum ThreadState {
        NOT_USING_THREAD, STARTED, UNSUBSCRIBED, TERMINAL
    }

    protected final HyperionCommandMetrics metrics;

    protected final HyperionCommandKey commandKey;
    protected final HyperionCommandGroupKey commandGroup;

    /**
     * Plugin implementations
     */
    protected final HystrixEventNotifier eventNotifier;
    protected final HystrixCommandExecutionHook executionHook;

    /* FALLBACK Semaphore */
    protected final TryableSemaphore fallbackSemaphoreOverride;
    /* each circuit has a semaphore to restrict concurrent fallback execution */
    protected static final ConcurrentHashMap<String, TryableSemaphore> fallbackSemaphorePerCircuit = new ConcurrentHashMap<String, TryableSemaphore>();
    /* END FALLBACK Semaphore */

    /* EXECUTION Semaphore */
    protected final TryableSemaphore executionSemaphoreOverride;
    /* each circuit has a semaphore to restrict concurrent fallback execution */
    protected static final ConcurrentHashMap<String, TryableSemaphore> executionSemaphorePerCircuit = new ConcurrentHashMap<String, TryableSemaphore>();
    /* END EXECUTION Semaphore */

    protected final AtomicReference<Reference<TimerListener>> timeoutTimer = new AtomicReference<Reference<TimerListener>>();

    protected AtomicReference<CommandState> commandState = new AtomicReference<CommandState>(CommandState.NOT_STARTED);
    protected AtomicReference<ThreadState> threadState = new AtomicReference<ThreadState>(ThreadState.NOT_USING_THREAD);

    /*
     * {@link ExecutionResult} refers to what happened as the user-provided code ran.  If request-caching is used,
     * then multiple command instances will have a reference to the same {@link ExecutionResult}.  So all values there
     * should be the same, even in the presence of request-caching.
     *
     * If some values are not properly shareable, then they belong on the command instance, so they are not visible to
     * other commands.
     *
     * Examples: RESPONSE_FROM_CACHE, CANCELLED HyperionEventTypes
     */
    protected volatile ExecutionResult executionResult = ExecutionResult.EMPTY; //state on shared execution

    protected volatile boolean isResponseFromCache = false;
    protected volatile ExecutionResult executionResultAtTimeOfCancellation;
    protected volatile long commandStartTimestamp = -1L;

    /* If this command executed and timed-out */
    protected final AtomicReference<TimedOutStatus> isCommandTimedOut = new AtomicReference<TimedOutStatus>(TimedOutStatus.NOT_EXECUTED);
    protected volatile Action0 endCurrentThreadExecutingCommand;

    /**
     * Instance of RequestCache logic
     */
    protected final HystrixRequestCache requestCache;
//    protected final HystrixRequestLog currentRequestLog;

    // this is a micro-optimization but saves about 1-2microseconds (on 2011 MacBook Pro) 
    // on the repetitive string processing that will occur on the same classes over and over again
    private static ConcurrentHashMap<Class<?>, String> defaultNameCache = new ConcurrentHashMap<Class<?>, String>();

    protected static ConcurrentHashMap<HyperionCommandKey, Boolean> commandContainsFallback = new ConcurrentHashMap<HyperionCommandKey, Boolean>();

    /* package */static String getDefaultNameFromClass(Class<?> cls) {
        String fromCache = defaultNameCache.get(cls);
        if (fromCache != null) {
            return fromCache;
        }
        // generate the default
        // default HyperionCommandKey to use if the method is not overridden
        String name = cls.getSimpleName();
        if (name.equals("")) {
            // we don't have a SimpleName (anonymous inner class) so use the full class name
            name = cls.getName();
            name = name.substring(name.lastIndexOf('.') + 1, name.length());
        }
        defaultNameCache.put(cls, name);
        return name;
    }

    protected AbstractCommand(HyperionCommandGroupKey group, HyperionCommandKey key, HyperionThreadPoolKey threadPoolKey, HyperionCircuitBreaker circuitBreaker, HyperionThreadPool threadPool,
            HyperionCommandProperties.Setter commandPropertiesDefaults, HyperionThreadPoolProperties.Setter threadPoolPropertiesDefaults,
            HyperionCommandMetrics metrics, TryableSemaphore fallbackSemaphore, TryableSemaphore executionSemaphore,
            HystrixPropertiesStrategy propertiesStrategy, HystrixCommandExecutionHook executionHook) {

        this.commandGroup = initGroupKey(group);
        this.commandKey = initCommandKey(key, getClass());
        this.properties = initCommandProperties(this.commandKey, propertiesStrategy, commandPropertiesDefaults);
        this.metrics = initMetrics(metrics, this.commandGroup, this.threadPoolKey, this.commandKey, this.properties);
        this.circuitBreaker = initCircuitBreaker(this.properties.circuitBreakerEnabled().get(), circuitBreaker, this.commandGroup, this.commandKey, this.properties, this.metrics);
        
	//////////////
	    this.threadPool = null;
	    this.threadPoolKey = null;
	  //Strategies from plugins
	    this.eventNotifier = null;
	    this.executionHook = null;
	
	    this.requestCache = null;
	
	    /* fallback semaphore override if applicable */
	    this.fallbackSemaphoreOverride = null;
	
	    /* execution semaphore override if applicable */
	    this.executionSemaphoreOverride = null;
        
    }
    
//    protected HyperionAbstractCommand(HyperionCommandGroupKey group, HyperionCommandKey key, 
//    		HyperionCircuitBreaker circuitBreaker,
//            HyperionCommandProperties.Setter commandPropertiesDefaults,
//            HyperionCommandMetrics metrics,
//            HystrixPropertiesStrategy propertiesStrategy) {
//
//        this.commandGroup = initGroupKey(group);
//        this.commandKey = initCommandKey(key, getClass());
//        this.properties = initCommandProperties(this.commandKey, propertiesStrategy, commandPropertiesDefaults);
//        this.metrics = initMetrics(metrics, this.commandGroup, null, this.commandKey, this.properties);
//        this.circuitBreaker = initCircuitBreaker(this.properties.circuitBreakerEnabled().get(), circuitBreaker, this.commandGroup, this.commandKey, this.properties, this.metrics);
//        
//        //////////////
//        this.threadPool = null;
//        this.threadPoolKey = null;
//      //Strategies from plugins
//        this.eventNotifier = null;
//        this.concurrencyStrategy = null;
//        this.executionHook = null;
//
//        this.requestCache = null;
//        this.currentRequestLog = null;
//
//        /* fallback semaphore override if applicable */
//        this.fallbackSemaphoreOverride = null;
//
//        /* execution semaphore override if applicable */
//        this.executionSemaphoreOverride = null;
//    }

    private static HyperionCommandGroupKey initGroupKey(final HyperionCommandGroupKey fromConstructor) {
        if (fromConstructor == null) {
            throw new IllegalStateException("HystrixCommandGroup can not be NULL");
        } else {
            return fromConstructor;
        }
    }

    private static HyperionCommandKey initCommandKey(final HyperionCommandKey fromConstructor, Class<?> clazz) {
        if (fromConstructor == null || fromConstructor.name().trim().equals("")) {
            final String keyName = getDefaultNameFromClass(clazz);
            return HyperionCommandKey.Factory.asKey(keyName);
        } else {
            return fromConstructor;
        }
    }

    private static HyperionCommandProperties initCommandProperties(HyperionCommandKey commandKey, HystrixPropertiesStrategy propertiesStrategy, HyperionCommandProperties.Setter commandPropertiesDefaults) {
//        if (propertiesStrategy == null) {
			return HyperionPropertiesFactory.getCommandProperties(commandKey, commandPropertiesDefaults);
//        } else {
//            // used for unit testing
//            return propertiesStrategy.getCommandProperties(commandKey, commandPropertiesDefaults);
//        }
    }

    /*
     * ThreadPoolKey
     *
     * This defines which thread-pool this command should run on.
     *
     * It uses the HystrixThreadPoolKey if provided, then defaults to use HystrixCommandGroup.
     *
     * It can then be overridden by a property if defined so it can be changed at runtime.
     */
    private static HyperionThreadPoolKey initThreadPoolKey(HyperionThreadPoolKey threadPoolKey, HyperionCommandGroupKey groupKey, String threadPoolKeyOverride) {
        if (threadPoolKeyOverride == null) {
            // we don't have a property overriding the value so use either HystrixThreadPoolKey or HystrixCommandGroup
            if (threadPoolKey == null) {
                /* use HystrixCommandGroup if HystrixThreadPoolKey is null */
                return HyperionThreadPoolKey.Factory.asKey(groupKey.name());
            } else {
                return threadPoolKey;
            }
        } else {
            // we have a property defining the thread-pool so use it instead
            return HyperionThreadPoolKey.Factory.asKey(threadPoolKeyOverride);
        }
    }

    private static HyperionCommandMetrics initMetrics(HyperionCommandMetrics fromConstructor, HyperionCommandGroupKey groupKey,
                                                     HyperionThreadPoolKey threadPoolKey, HyperionCommandKey commandKey,
                                                     HyperionCommandProperties properties) {
        if (fromConstructor == null) {
            return HyperionCommandMetrics.getInstance(commandKey, groupKey, threadPoolKey, properties);
        } else {
            return fromConstructor;
        }
    }

    private static HyperionCircuitBreaker initCircuitBreaker(boolean enabled, HyperionCircuitBreaker fromConstructor,
                                                            HyperionCommandGroupKey groupKey, HyperionCommandKey commandKey,
                                                            HyperionCommandProperties properties, HyperionCommandMetrics metrics) {
        if (enabled) {
            if (fromConstructor == null) {
                // get the default implementation of HyperionCircuitBreaker
                return HyperionCircuitBreaker.Factory.getInstance(commandKey, groupKey, properties, metrics);
            } else {
                return fromConstructor;
            }
        } else {
            return new NoOpCircuitBreaker();
        }
    }


//    private static HystrixThreadPool initThreadPool(HystrixThreadPool fromConstructor, HystrixThreadPoolKey threadPoolKey, HystrixThreadPoolProperties.Setter threadPoolPropertiesDefaults) {
//        if (fromConstructor == null) {
//            // get the default implementation of HystrixThreadPool
//            return HystrixThreadPool.Factory.getInstance(threadPoolKey, threadPoolPropertiesDefaults);
//        } else {
//            return fromConstructor;
//        }
//    }


    /**
     * Allow the Collapser to mark this command instance as being used for a collapsed request and how many requests were collapsed.
     * 
     * @param sizeOfBatch number of commands in request batch
     */
//    /* package */void markAsCollapsedCommand(HystrixCollapserKey collapserKey, int sizeOfBatch) {
//        eventNotifier.markEvent(HyperionEventType.COLLAPSED, this.commandKey);
//        executionResult = executionResult.markCollapsed(collapserKey, sizeOfBatch);
//    }

    /**
     * Used for asynchronous execution of command with a callback by subscribing to the {@link Observable}.
     * <p>
     * This eagerly starts execution of the command the same as {@link HystrixCommand#queue()} and {@link HystrixCommand#execute()}.
     * <p>
     * A lazy {@link Observable} can be obtained from {@link #toObservable()}.
     * <p>
     * See https://github.com/Netflix/RxJava/wiki for more information.
     * 
     * @return {@code Observable<R>} that executes and calls back with the result of command execution or a fallback if the command fails for any reason.
     * @throws HyperionRuntimeException
     *             if a fallback does not exist
     *             <p>
     *             <ul>
     *             <li>via {@code Observer#onError} if a failure occurs</li>
     *             <li>or immediately if the command can not be queued (such as short-circuited, thread-pool/semaphore rejected)</li>
     *             </ul>
     * @throws HyperionBadRequestException
     *             via {@code Observer#onError} if invalid arguments or state were used representing a user failure, not a system failure
     * @throws IllegalStateException
     *             if invoked more than once
     */
    public Observable<R> observe() {
        // us a ReplaySubject to buffer the eagerly subscribed-to Observable
        ReplaySubject<R> subject = ReplaySubject.create();
        // eagerly kick off subscription
        final Subscription sourceSubscription = toObservable().subscribe(subject);
        // return the subject that can be subscribed to later while the execution has already started
        return subject.doOnUnsubscribe(new Action0() {
            @Override
            public void call() {
                sourceSubscription.unsubscribe();
            }
        });
    }

    protected abstract Observable<R> getExecutionObservable();

    protected abstract Observable<R> getFallbackObservable();

    /**
     * Used for asynchronous execution of command with a callback by subscribing to the {@link Observable}.
     * <p>
     * This lazily starts execution of the command once the {@link Observable} is subscribed to.
     * <p>
     * An eager {@link Observable} can be obtained from {@link #observe()}.
     * <p>
     * See https://github.com/ReactiveX/RxJava/wiki for more information.
     * 
     * @return {@code Observable<R>} that executes and calls back with the result of command execution or a fallback if the command fails for any reason.
     * @throws HyperionRuntimeException
     *             if a fallback does not exist
     *             <p>
     *             <ul>
     *             <li>via {@code Observer#onError} if a failure occurs</li>
     *             <li>or immediately if the command can not be queued (such as short-circuited, thread-pool/semaphore rejected)</li>
     *             </ul>
     * @throws HyperionBadRequestException
     *             via {@code Observer#onError} if invalid arguments or state were used representing a user failure, not a system failure
     * @throws IllegalStateException
     *             if invoked more than once
     */
    public Observable<R> toObservable() {
        final AbstractCommand<R> _cmd = this;

        //doOnCompleted handler already did all of the SUCCESS work
        //doOnError handler already did all of the FAILURE/TIMEOUT/REJECTION/BAD_REQUEST work
        final Action0 terminateCommandCleanup = new Action0() {

            @Override
            public void call() {
                if (_cmd.commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.TERMINAL)) {
//                	System.out.println("chain-------");
                    handleCommandEnd(false); //user code never ran
                } else if (_cmd.commandState.compareAndSet(CommandState.USER_CODE_EXECUTED, CommandState.TERMINAL)) {
//                	System.out.println("code-------");
                    handleCommandEnd(true); //user code did run
                }
            }
        };

        //mark the command as CANCELLED and store the latency (in addition to standard cleanup)
        final Action0 unsubscribeCommandCleanup = new Action0() {
            @Override
            public void call() {
                circuitBreaker.markNonSuccess();
                if (_cmd.commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.UNSUBSCRIBED)) {
                    if (!_cmd.executionResult.containsTerminalEvent()) {
//                        _cmd.eventNotifier.markEvent(HyperionEventType.CANCELLED, _cmd.commandKey);
                        try {
//                            executionHook.onUnsubscribe(_cmd);
                        } catch (Throwable hookEx) {
                            logger.warn("Error calling HystrixCommandExecutionHook.onUnsubscribe", hookEx);
                        }
                        _cmd.executionResultAtTimeOfCancellation = _cmd.executionResult
                                .addEvent((int) (System.currentTimeMillis() - _cmd.commandStartTimestamp), HyperionEventType.CANCELLED);
                    }
                    handleCommandEnd(false); //user code never ran
                } else if (_cmd.commandState.compareAndSet(CommandState.USER_CODE_EXECUTED, CommandState.UNSUBSCRIBED)) {
                    if (!_cmd.executionResult.containsTerminalEvent()) {
//                        _cmd.eventNotifier.markEvent(HyperionEventType.CANCELLED, _cmd.commandKey);
                        try {
//                            executionHook.onUnsubscribe(_cmd);
                        } catch (Throwable hookEx) {
                            logger.warn("Error calling HystrixCommandExecutionHook.onUnsubscribe", hookEx);
                        }
                        _cmd.executionResultAtTimeOfCancellation = _cmd.executionResult
                                .addEvent((int) (System.currentTimeMillis() - _cmd.commandStartTimestamp), HyperionEventType.CANCELLED);
                    }
                    handleCommandEnd(true); //user code did run
                }
            }
        };

        final Func0<Observable<R>> applyHystrixSemantics = new Func0<Observable<R>>() {
            @Override
            public Observable<R> call() {
                if (commandState.get().equals(CommandState.UNSUBSCRIBED)) {
                    return Observable.never();
                }
                return applyHystrixSemantics(_cmd);
            }
        };

        final Func1<R, R> wrapWithAllOnNextHooks = new Func1<R, R>() {
            @Override
            public R call(R r) {
            	
                R afterFirstApplication = r;

                try {
//                    afterFirstApplication = executionHook.onComplete(_cmd, r);
                	afterFirstApplication = r;
                } catch (Throwable hookEx) {
                    logger.warn("Error calling HystrixCommandExecutionHook.onComplete", hookEx);
                }

                try {
//                    return executionHook.onEmit(_cmd, afterFirstApplication);
                	return afterFirstApplication;
                } catch (Throwable hookEx) {
                    logger.warn("Error calling HystrixCommandExecutionHook.onEmit", hookEx);
                    return afterFirstApplication;
                }
            }
        };

        final Action0 fireOnCompletedHook = new Action0() {
            @Override
            public void call() {
                try {
//                    executionHook.onSuccess(_cmd);
                } catch (Throwable hookEx) {
                    logger.warn("Error calling HystrixCommandExecutionHook.onSuccess", hookEx);
                }
            }
        };

        return Observable.defer(new Func0<Observable<R>>() {
            @Override
            public Observable<R> call() {
//            	System.out.println("--------------------begin toObservable.call()");
                 /* this is a stateful object so can only be used once */
//            	System.out.println("-1111-------" + commandState.get());
                if (!commandState.compareAndSet(CommandState.NOT_STARTED, CommandState.OBSERVABLE_CHAIN_CREATED)) {
                    IllegalStateException ex = new IllegalStateException("This instance can only be executed once. Please instantiate a new instance.");
                    //TODO make a new error type for this

                    throw new RuntimeException();
//                    throw new HyperionRuntimeException(FailureType.BAD_REQUEST_EXCEPTION, _cmd.getClass(), getLogMessagePrefix() + " command executed multiple times - this is not permitted.", ex, null);
                }

                commandStartTimestamp = System.currentTimeMillis();

                if (properties.requestLogEnabled().get()) {
                    // log this command execution regardless of what happened
//                    if (currentRequestLog != null) {
//                        currentRequestLog.addExecutedCommand(_cmd);
//                    }
                }

                final boolean requestCacheEnabled = isRequestCachingEnabled();
                final String cacheKey = getCacheKey();

                /* try from cache first */
//                if (requestCacheEnabled) {
//                    HystrixCommandResponseFromCache<R> fromCache = (HystrixCommandResponseFromCache<R>) requestCache.get(cacheKey);
//                    if (fromCache != null) {
//                        isResponseFromCache = true;
//                        return handleRequestCacheHitAndEmitValues(fromCache, _cmd);
//                    }
//                }

                Observable<R> hystrixObservable =
                        Observable.defer(applyHystrixSemantics)
                                .map(wrapWithAllOnNextHooks);	// 没啥用？？？////////

                Observable<R> afterCache;

                // put in cache
//                if (requestCacheEnabled && cacheKey != null) {
//                    // wrap it for caching
//                    HystrixCachedObservable<R> toCache = HystrixCachedObservable.from(hystrixObservable, _cmd);
//                    HystrixCommandResponseFromCache<R> fromCache = (HystrixCommandResponseFromCache<R>) requestCache.putIfAbsent(cacheKey, toCache);
//                    if (fromCache != null) {
//                        // another thread beat us so we'll use the cached value instead
//                        toCache.unsubscribe();
//                        isResponseFromCache = true;
//                        return handleRequestCacheHitAndEmitValues(fromCache, _cmd);
//                    } else {
//                        // we just created an ObservableCommand so we cast and return it
//                        afterCache = toCache.toObservable();
//                    }
//                } else {
                    afterCache = hystrixObservable;
//                }

//                System.out.println("--------------------end toObservable.call()");
                return afterCache
                        .doOnTerminate(terminateCommandCleanup)     // perform cleanup once (either on normal terminal state (this line), or unsubscribe (next line))
                        .doOnUnsubscribe(unsubscribeCommandCleanup) // perform cleanup once
                        .doOnCompleted(fireOnCompletedHook);
            }
        });
    }

    private Observable<R> applyHystrixSemantics(final AbstractCommand<R> _cmd) {
//    	System.out.println("begin applyHystrixSemantics()");
        // mark that we're starting execution on the ExecutionHook
        // if this hook throws an exception, then a fast-fail occurs with no fallback.  No state is left inconsistent
//        executionHook.onStart(_cmd);

        /* determine if we're allowed to execute */
        if (circuitBreaker.attemptExecution()) {
            final TryableSemaphore executionSemaphore = getExecutionSemaphore();
            final AtomicBoolean semaphoreHasBeenReleased = new AtomicBoolean(false);
            final Action0 singleSemaphoreRelease = new Action0() {
                @Override
                public void call() {
                    if (semaphoreHasBeenReleased.compareAndSet(false, true)) {
                        executionSemaphore.release();
                    }
                }
            };

//            final Action1<Throwable> markExceptionThrown = new Action1<Throwable>() {
//                @Override
//                public void call(Throwable t) {
////                	System.out.println("-----------299299199191992191292919921992111111111111111111");
////                    eventNotifier.markEvent(HyperionEventType.EXCEPTION_THROWN, commandKey);
//                }
//            };

            if (executionSemaphore.tryAcquire()) {
                try {
                    /* used to track userThreadExecutionTime */
                    executionResult = executionResult.setInvocationStartTime(System.currentTimeMillis());
                    return executeCommandAndObserve(_cmd)
//                            .doOnError(markExceptionThrown)	// 没啥用？？///////
                            .doOnTerminate(singleSemaphoreRelease)
                            .doOnUnsubscribe(singleSemaphoreRelease);
                } catch (RuntimeException e) {
                    return Observable.error(e);
                }
            } else {
                return handleSemaphoreRejectionViaFallback();
            }
        } else {
            return handleShortCircuitViaFallback();
        }
    }

    abstract protected boolean commandIsScalar();

    /**
     * This decorates "Hystrix" functionality around the run() Observable.
     *
     * @return R
     */
    private Observable<R> executeCommandAndObserve(final AbstractCommand<R> _cmd) {
//    	System.out.println("begin executeCommandAndObserve()");
        final HystrixRequestContext currentRequestContext = HystrixRequestContext.getContextForCurrentThread();

        final Action1<R> markEmits = new Action1<R>() {
            @Override
            public void call(R r) {
                if (shouldOutputOnNextEvents()) {
                    executionResult = executionResult.addEvent(HyperionEventType.EMIT);
//                    eventNotifier.markEvent(HyperionEventType.EMIT, commandKey);
                }
                if (commandIsScalar()) {
                    long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
//                    eventNotifier.markEvent(HyperionEventType.SUCCESS, commandKey);
                    executionResult = executionResult.addEvent((int) latency, HyperionEventType.SUCCESS);
//                    System.out.println("---in executeCommandAndObserve.doOnNext, result =" + executionResult);
//                    eventNotifier.markCommandExecution(getCommandKey(), properties.executionIsolationStrategy().get(), (int) latency, executionResult.getOrderedList());
                    circuitBreaker.markSuccess();
                }
            }
        };

        final Action0 markOnCompleted = new Action0() {
            @Override
            public void call() {	// 不可达/////////
                if (!commandIsScalar()) {
                    long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
//                    eventNotifier.markEvent(HyperionEventType.SUCCESS, commandKey);
                    executionResult = executionResult.addEvent((int) latency, HyperionEventType.SUCCESS);
//                    eventNotifier.markCommandExecution(getCommandKey(), properties.executionIsolationStrategy().get(), (int) latency, executionResult.getOrderedList());
                    circuitBreaker.markSuccess();	// 不可达/////////
                }
            }
        };

        final Func1<Throwable, Observable<R>> handleFallback = new Func1<Throwable, Observable<R>>() {
            @Override
            public Observable<R> call(Throwable t) {
//            	t.printStackTrace();
                circuitBreaker.markNonSuccess();
                Exception e = getExceptionFromThrowable(t);
                executionResult = executionResult.setExecutionException(e);
                if (e instanceof RejectedExecutionException) {
                    return handleThreadPoolRejectionViaFallback(e);
                } else if (t instanceof HyperionTimeoutException) {
                    return handleTimeoutViaFallback();
                } else if (t instanceof HyperionBadRequestException) {
                    return handleBadRequestByEmittingError(e);
                } else {
                    /*
                     * Treat HyperionBadRequestException from ExecutionHook like a plain HyperionBadRequestException.
                     */
                	// 如果是HyperionBadRequestException，不会触发fallback，需要应用程序捕获
                    if (e instanceof HyperionBadRequestException) {
//                        eventNotifier.markEvent(HyperionEventType.BAD_REQUEST, commandKey);
                        return Observable.error(e);
                    }
                    
                    // run出错的同时还会执行getfallback
                    return handleFailureViaFallback(e);
                }
            }
        };

        final Action1<Notification<? super R>> setRequestContext = new Action1<Notification<? super R>>() {
            @Override
            public void call(Notification<? super R> rNotification) {
//            	System.out.println("---in dddd, result =" + currentRequestContext);
                setRequestContextIfNeeded(currentRequestContext);
            }
        };

        Observable<R> execution;
        if (properties.executionTimeoutEnabled().get()) {
            execution = executeCommandWithSpecifiedIsolation(_cmd);
//                    .lift(new HystrixObservableTimeoutOperator<R>(_cmd));//////////
        } else {
            execution = executeCommandWithSpecifiedIsolation(_cmd);
        }

//        System.out.println("end executeCommandAndObserve()");
        return execution.doOnNext(markEmits)
                .doOnCompleted(markOnCompleted)	// 没啥用？？？////////
                .onErrorResumeNext(handleFallback)	// Observable.error()发过来会触发此处
                .doOnEach(setRequestContext);	// 没啥用？？？////////
    }

    private Observable<R> executeCommandWithSpecifiedIsolation(final AbstractCommand<R> _cmd) {
//    	System.out.println("begin executeCommandWithSpecifiedIsolation()");
        if (properties.executionIsolationStrategy().get() == ExecutionIsolationStrategy.THREAD) {
            // mark that we are executing in a thread (even if we end up being rejected we still were a THREAD execution and not SEMAPHORE)
            return Observable.defer(new Func0<Observable<R>>() {
                @Override
                public Observable<R> call() {
//                	System.out.println("begin executeCommandWithSpecifiedIsolation.call()");
                    executionResult = executionResult.setExecutionOccurred();
                    if (!commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.USER_CODE_EXECUTED)) {
                        return Observable.error(new IllegalStateException("execution attempted while in state : " + commandState.get().name()));
                    }

//                    metrics.markCommandStart(commandKey, threadPoolKey, ExecutionIsolationStrategy.THREAD);///

                    if (isCommandTimedOut.get() == TimedOutStatus.TIMED_OUT) {
                        // the command timed out in the wrapping thread so we will return immediately
                        // and not increment any of the counters below or other such logic
                        return Observable.error(new RuntimeException("timed out before executing run()"));
                    }
                    if (threadState.compareAndSet(ThreadState.NOT_USING_THREAD, ThreadState.STARTED)) {
                        //we have not been unsubscribed, so should proceed
//                        HystrixCounters.incrementGlobalConcurrentThreads();
                        ///////////////
//                        threadPool.markThreadExecution();
                        // store the command that is being run
                        endCurrentThreadExecutingCommand = Hyperion.startCurrentThreadExecutingCommand(getCommandKey());
                        executionResult = executionResult.setExecutedInThread();
                        /**
                         * If any of these hooks throw an exception, then it appears as if the actual execution threw an error
                         */
                        try {
//                            executionHook.onThreadStart(_cmd);
//                            executionHook.onRunStart(_cmd);
//                            executionHook.onExecutionStart(_cmd);
//                        	System.out.println("end executeCommandWithSpecifiedIsolation.call()");
                            return getUserExecutionObservable(_cmd);
                        } catch (Throwable ex) {
                            return Observable.error(ex);
                        }
                    } else {
                        //command has already been unsubscribed, so return immediately
                        return Observable.empty();
                    }
                }
            }).doOnTerminate(new Action0() {
                @Override
                public void call() {
//                	System.out.println("---in executeCommandWithSpecifiedIsolation.doOnTerminate()");
                    if (threadState.compareAndSet(ThreadState.STARTED, ThreadState.TERMINAL)) {
//                        handleThreadEnd(_cmd);
                    }
                    if (threadState.compareAndSet(ThreadState.NOT_USING_THREAD, ThreadState.TERMINAL)) {
                        //if it was never started and received terminal, then no need to clean up (I don't think this is possible)
                    }
                    //if it was unsubscribed, then other cleanup handled it
                }
            }).doOnUnsubscribe(new Action0() {
                @Override
                public void call() {
//                	System.out.println("---in executeCommandWithSpecifiedIsolation.doOnUnsubscribe()");

                    if (threadState.compareAndSet(ThreadState.STARTED, ThreadState.UNSUBSCRIBED)) {
//                        handleThreadEnd(_cmd);
                    }
                    if (threadState.compareAndSet(ThreadState.NOT_USING_THREAD, ThreadState.UNSUBSCRIBED)) {
                        //if it was never started and was cancelled, then no need to clean up
                    }
                    //if it was terminal, then other cleanup handled it
                }
            });
//                .subscribeOn(threadPool.getScheduler(new Func0<Boolean>() {
//                @Override
//                public Boolean call() {
//                    return properties.executionIsolationThreadInterruptOnTimeout().get() && _cmd.isCommandTimedOut.get() == TimedOutStatus.TIMED_OUT;
//                }
//            }));
        } else {
            return Observable.defer(new Func0<Observable<R>>() {
                @Override
                public Observable<R> call() {
                    executionResult = executionResult.setExecutionOccurred();
                    if (!commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.USER_CODE_EXECUTED)) {
                        return Observable.error(new IllegalStateException("execution attempted while in state : " + commandState.get().name()));
                    }

//                    metrics.markCommandStart(commandKey, threadPoolKey, ExecutionIsolationStrategy.SEMAPHORE);
                    // semaphore isolated
                    // store the command that is being run
                    endCurrentThreadExecutingCommand = Hyperion.startCurrentThreadExecutingCommand(getCommandKey());
                    try {
//                        executionHook.onRunStart(_cmd);
//                        executionHook.onExecutionStart(_cmd);
                        return getUserExecutionObservable(_cmd);  //the getUserExecutionObservable method already wraps sync exceptions, so this shouldn't throw
                    } catch (Throwable ex) {
                        //If the above hooks throw, then use that as the result of the run method
                        return Observable.error(ex);
                    }
                }
            });
        }
    }

    /**
     * Execute <code>getFallback()</code> within protection of a semaphore that limits number of concurrent executions.
     * <p>
     * Fallback implementations shouldn't perform anything that can be blocking, but we protect against it anyways in case someone doesn't abide by the contract.
     * <p>
     * If something in the <code>getFallback()</code> implementation is latent (such as a network call) then the semaphore will cause us to start rejecting requests rather than allowing potentially
     * all threads to pile up and block.
     *
     * @return K
     * @throws UnsupportedOperationException
     *             if getFallback() not implemented
     * @throws HyperionRuntimeException
     *             if getFallback() fails (throws an Exception) or is rejected by the semaphore
     */
    private Observable<R> getFallbackOrThrowException(final AbstractCommand<R> _cmd, final HyperionEventType eventType, final FailureType failureType, final String message, final Exception originalException) {
//    	System.out.println("begin getFallbackOrThrowException()");
        final HystrixRequestContext requestContext = HystrixRequestContext.getContextForCurrentThread();
        long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
        // record the executionResult
        // do this before executing fallback so it can be queried from within getFallback (see See https://github.com/Netflix/Hystrix/pull/144)
        executionResult = executionResult.addEvent((int) latency, eventType);

        if (isUnrecoverable(originalException)) {
//        if (true) {
//        	System.out.println("isUnrecoverable originalException-----");
            logger.error("Unrecoverable Error for HystrixCommand so will throw HyperionRuntimeException and not apply fallback. ", originalException);

            /* executionHook for all errors */
            Exception e = wrapWithOnErrorHook(failureType, originalException);
            return Observable.error(new HyperionRuntimeException(failureType, this.getClass(), getLogMessagePrefix() + " " + message + " and encountered unrecoverable error.", e, null));
//            return Observable.error(new RuntimeException(this.getClass().toString() +  getLogMessagePrefix() + " " + message + " and encountered unrecoverable error."));
            
        } else {
//        	System.out.println("no isUnrecoverable originalException-----");
            if (isRecoverableError(originalException)) {
                logger.warn("Recovered from java.lang.Error by serving Hystrix fallback", originalException);
            }

            if (properties.fallbackEnabled().get()) {
                /* fallback behavior is permitted so attempt */

                final Action1<Notification<? super R>> setRequestContext = new Action1<Notification<? super R>>() {
                    @Override
                    public void call(Notification<? super R> rNotification) {
                        setRequestContextIfNeeded(requestContext);
                    }
                };

                final Action1<R> markFallbackEmit = new Action1<R>() {
                    @Override
                    public void call(R r) {
//                    	System.out.println("-----markFallbackEmit----------------------");
                        if (shouldOutputOnNextEvents()) {
                            executionResult = executionResult.addEvent(HyperionEventType.FALLBACK_EMIT);
//                            eventNotifier.markEvent(HyperionEventType.FALLBACK_EMIT, commandKey);
                        }
                    }
                };

                final Action0 markFallbackCompleted = new Action0() {
                    @Override
                    public void call() {
//                    	System.out.println("-----markFallbackCompleted");
                        long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
//                        eventNotifier.markEvent(HyperionEventType.FALLBACK_SUCCESS, commandKey);
                        executionResult = executionResult.addEvent((int) latency, HyperionEventType.FALLBACK_SUCCESS);
                    }
                };

                final Func1<Throwable, Observable<R>> handleFallbackError = new Func1<Throwable, Observable<R>>() {
                    @Override
                    public Observable<R> call(Throwable t) {
//                    	System.out.println("-----handleFallbackError");
                        /* executionHook for all errors */
                        Exception e = wrapWithOnErrorHook(failureType, originalException);
                        Exception fe = getExceptionFromThrowable(t);

                        long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
                        Exception toEmit;

                        if (fe instanceof UnsupportedOperationException) {
                            logger.debug("No fallback for HystrixCommand. ", fe); // debug only since we're throwing the exception and someone higher will do something with it
//                            eventNotifier.markEvent(HyperionEventType.FALLBACK_MISSING, commandKey);
                            executionResult = executionResult.addEvent((int) latency, HyperionEventType.FALLBACK_MISSING);

//                            toEmit = new HyperionRuntimeException(failureType, _cmd.getClass(), getLogMessagePrefix() + " " + message + " and no fallback available.", e, fe);
                            toEmit = new RuntimeException(this.getClass().toString() +  getLogMessagePrefix() + " " + message + " and no fallback available.");
                        } else {
                            logger.debug("HystrixCommand execution " + failureType.name() + " and fallback failed.", fe);
//                            eventNotifier.markEvent(HyperionEventType.FALLBACK_FAILURE, commandKey);
                            executionResult = executionResult.addEvent((int) latency, HyperionEventType.FALLBACK_FAILURE);

//                            toEmit = new HyperionRuntimeException(failureType, _cmd.getClass(), getLogMessagePrefix() + " " + message + " and fallback failed.", e, fe);
                            toEmit = new RuntimeException(this.getClass().toString() +  getLogMessagePrefix() + " " + message + " and fallback failed.");
                        }

                        // NOTE: we're suppressing fallback exception here
                        if (shouldNotBeWrapped(originalException)) {
                            return Observable.error(e);
                        }
                        return Observable.error(toEmit);
                    }
                };

                final TryableSemaphore fallbackSemaphore = getFallbackSemaphore();
                final AtomicBoolean semaphoreHasBeenReleased = new AtomicBoolean(false);
                final Action0 singleSemaphoreRelease = new Action0() {
                    @Override
                    public void call() {
                        if (semaphoreHasBeenReleased.compareAndSet(false, true)) {
                            fallbackSemaphore.release();
                        }
                    }
                };

                Observable<R> fallbackExecutionChain;

                // acquire a permit
                if (fallbackSemaphore.tryAcquire()) {
                    try {
                        if (isFallbackUserDefined()) {
//                            executionHook.onFallbackStart(this);
                            fallbackExecutionChain = getFallbackObservable();
                        } else {
                            //same logic as above without the hook invocation
                            fallbackExecutionChain = getFallbackObservable();
                        }
                    } catch (Throwable ex) {
                        //If hook or user-fallback throws, then use that as the result of the fallback lookup
                        fallbackExecutionChain = Observable.error(ex);
                    }

                    return fallbackExecutionChain
                            .doOnEach(setRequestContext)
//                            .lift(new FallbackHookApplication(_cmd))
//                            .lift(new DeprecatedOnFallbackHookApplication(_cmd))
                            .doOnNext(markFallbackEmit)
                            .doOnCompleted(markFallbackCompleted)
                            .onErrorResumeNext(handleFallbackError)	// 前面不出错就不会触发此处
                            .doOnTerminate(singleSemaphoreRelease)
                            .doOnUnsubscribe(singleSemaphoreRelease);
                } else {
                   return handleFallbackRejectionByEmittingError();
                }
            } else {
                return handleFallbackDisabledByEmittingError(originalException, failureType, message);
            }
        }
    }

    private Observable<R> getUserExecutionObservable(final AbstractCommand<R> _cmd) {
//    	System.out.println("begin getUserExecutionObservable()");
        Observable<R> userObservable;

        try {
            userObservable = getExecutionObservable();
        } catch (Throwable ex) {
            // the run() method is a user provided implementation so can throw instead of using Observable.onError
            // so we catch it here and turn it into Observable.error
            userObservable = Observable.error(ex);
        }
        
//        System.out.println("end getUserExecutionObservable()");
        return userObservable;
//        return userObservable
//                .lift(new ExecutionHookApplication(_cmd))
//                .lift(new DeprecatedOnRunHookApplication(_cmd));
    }
//
//    private Observable<R> handleRequestCacheHitAndEmitValues(final HystrixCommandResponseFromCache<R> fromCache, final HyperionAbstractCommand<R> _cmd) {
//        try {
//            executionHook.onCacheHit(this);
//        } catch (Throwable hookEx) {
//            logger.warn("Error calling HystrixCommandExecutionHook.onCacheHit", hookEx);
//        }
//
//        return fromCache.toObservableWithStateCopiedInto(this)
//                .doOnTerminate(new Action0() {
//                    @Override
//                    public void call() {
//                        if (commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.TERMINAL)) {
//                            cleanUpAfterResponseFromCache(false); //user code never ran
//                        } else if (commandState.compareAndSet(CommandState.USER_CODE_EXECUTED, CommandState.TERMINAL)) {
//                            cleanUpAfterResponseFromCache(true); //user code did run
//                        }
//                    }
//                })
//                .doOnUnsubscribe(new Action0() {
//                    @Override
//                    public void call() {
//                        if (commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.UNSUBSCRIBED)) {
//                            cleanUpAfterResponseFromCache(false); //user code never ran
//                        } else if (commandState.compareAndSet(CommandState.USER_CODE_EXECUTED, CommandState.UNSUBSCRIBED)) {
//                            cleanUpAfterResponseFromCache(true); //user code did run
//                        }
//                    }
//                });
//    }

    private void cleanUpAfterResponseFromCache(boolean commandExecutionStarted) {
        Reference<TimerListener> tl = timeoutTimer.get();
        if (tl != null) {
            tl.clear();
        }

        final long latency = System.currentTimeMillis() - commandStartTimestamp;
        executionResult = executionResult
                .addEvent(-1, HyperionEventType.RESPONSE_FROM_CACHE)
                .markUserThreadCompletion(latency)
                .setNotExecutedInThread();
        ExecutionResult cacheOnlyForMetrics = ExecutionResult.from(HyperionEventType.RESPONSE_FROM_CACHE)
                .markUserThreadCompletion(latency);
        metrics.markCommandDone(cacheOnlyForMetrics, commandKey, threadPoolKey, commandExecutionStarted);
//        eventNotifier.markEvent(HyperionEventType.RESPONSE_FROM_CACHE, commandKey);
    }

    private void handleCommandEnd(boolean commandExecutionStarted) {
        Reference<TimerListener> tl = timeoutTimer.get();
        if (tl != null) {
            tl.clear();
        }

        long userThreadLatency = System.currentTimeMillis() - commandStartTimestamp;
        executionResult = executionResult.markUserThreadCompletion((int) userThreadLatency);
//        System.out.println("handleCommandEnd()-----" + commandStartTimestamp
//        		+ "," + executionResultAtTimeOfCancellation	//null
//        		+ "," + endCurrentThreadExecutingCommand
//        		+ "," + threadPoolKey);	//null
        if (executionResultAtTimeOfCancellation == null) {
            metrics.markCommandDone(executionResult, commandKey, threadPoolKey, commandExecutionStarted);
        } else {
            metrics.markCommandDone(executionResultAtTimeOfCancellation, commandKey, threadPoolKey, commandExecutionStarted);
        }

        if (endCurrentThreadExecutingCommand != null) {
            endCurrentThreadExecutingCommand.call();
        }
    }

    private Observable<R> handleSemaphoreRejectionViaFallback() {
        Exception semaphoreRejectionException = new RuntimeException("could not acquire a semaphore for execution");
        executionResult = executionResult.setExecutionException(semaphoreRejectionException);
//        eventNotifier.markEvent(HyperionEventType.SEMAPHORE_REJECTED, commandKey);
        logger.debug("HystrixCommand Execution Rejection by Semaphore."); // debug only since we're throwing the exception and someone higher will do something with it
        // retrieve a fallback or throw an exception if no fallback available
        return getFallbackOrThrowException(this, HyperionEventType.SEMAPHORE_REJECTED, FailureType.REJECTED_SEMAPHORE_EXECUTION,
                "could not acquire a semaphore for execution", semaphoreRejectionException);
    }

    private Observable<R> handleShortCircuitViaFallback() {
        // record that we are returning a short-circuited fallback
//        eventNotifier.markEvent(HyperionEventType.SHORT_CIRCUITED, commandKey);
        // short-circuit and go directly to fallback (or throw an exception if no fallback implemented)
        Exception shortCircuitException = new RuntimeException("Hystrix circuit short-circuited and is OPEN");
        executionResult = executionResult.setExecutionException(shortCircuitException);
        try {
            return getFallbackOrThrowException(this, HyperionEventType.SHORT_CIRCUITED, FailureType.SHORTCIRCUIT,
                    "short-circuited", shortCircuitException);
        } catch (Exception e) {
            return Observable.error(e);
        }
    }

    private Observable<R> handleThreadPoolRejectionViaFallback(Exception underlying) {
//        eventNotifier.markEvent(HyperionEventType.THREAD_POOL_REJECTED, commandKey);
        threadPool.markThreadRejection();
        // use a fallback instead (or throw exception if not implemented)
        return getFallbackOrThrowException(this, HyperionEventType.THREAD_POOL_REJECTED, FailureType.REJECTED_THREAD_EXECUTION, "could not be queued for execution", underlying);
    }

    private Observable<R> handleTimeoutViaFallback() {
        return getFallbackOrThrowException(this, HyperionEventType.TIMEOUT, FailureType.TIMEOUT, "timed-out", new TimeoutException());
    }

    private Observable<R> handleBadRequestByEmittingError(Exception underlying) {
        Exception toEmit = underlying;

        try {
            long executionLatency = System.currentTimeMillis() - executionResult.getStartTimestamp();
//            eventNotifier.markEvent(HyperionEventType.BAD_REQUEST, commandKey);
            executionResult = executionResult.addEvent((int) executionLatency, HyperionEventType.BAD_REQUEST);
            /////////////
//            Exception decorated = executionHook.onError(this, HyperionRuntimeException.FailureType.BAD_REQUEST_EXCEPTION, underlying);
//
//            if (decorated instanceof HyperionBadRequestException) {
//                toEmit = decorated;
//            } else {
//                logger.warn("ExecutionHook.onError returned an exception that was not an instance of HyperionBadRequestException so will be ignored.", decorated);
//            }
        } catch (Exception hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onError", hookEx);
        }
        /*
         * HyperionBadRequestException is treated differently and allowed to propagate without any stats tracking or fallback logic
         */
//        return Observable.never();
        return Observable.error(toEmit);
    }

    private Observable<R> handleFailureViaFallback(Exception underlying) {
        /**
         * All other error handling
         */
        logger.debug("Error executing HystrixCommand.run(). Proceeding to fallback logic ...", underlying);

        // report failure
//        eventNotifier.markEvent(HyperionEventType.FAILURE, commandKey);

        // record the exception
        executionResult = executionResult.setException(underlying);
        return getFallbackOrThrowException(this, HyperionEventType.FAILURE, FailureType.COMMAND_EXCEPTION, "failed", underlying);
    }

    private Observable<R> handleFallbackRejectionByEmittingError() {
        long latencyWithFallback = System.currentTimeMillis() - executionResult.getStartTimestamp();
//        eventNotifier.markEvent(HyperionEventType.FALLBACK_REJECTION, commandKey);
        executionResult = executionResult.addEvent((int) latencyWithFallback, HyperionEventType.FALLBACK_REJECTION);
        logger.debug("HystrixCommand Fallback Rejection."); // debug only since we're throwing the exception and someone higher will do something with it
        // if we couldn't acquire a permit, we "fail fast" by throwing an exception
        return Observable.error(new HyperionRuntimeException(FailureType.REJECTED_SEMAPHORE_FALLBACK, this.getClass(), getLogMessagePrefix() + " fallback execution rejected.", null, null));
    }

    private Observable<R> handleFallbackDisabledByEmittingError(Exception underlying, FailureType failureType, String message) {
        /* fallback is disabled so throw HyperionRuntimeException */
        logger.debug("Fallback disabled for HystrixCommand so will throw HyperionRuntimeException. ", underlying); // debug only since we're throwing the exception and someone higher will do something with it

        /* executionHook for all errors */
        Exception wrapped = wrapWithOnErrorHook(failureType, underlying);
        return Observable.error(new HyperionRuntimeException(failureType, this.getClass(), getLogMessagePrefix() + " " + message + " and fallback disabled.", wrapped, null));
    }

    protected boolean shouldNotBeWrapped(Throwable underlying) {
        return underlying instanceof ExceptionNotWrappedByHyperion;
    }

    /**
     * Returns true iff the t was caused by a java.lang.Error that is unrecoverable.  Note: not all java.lang.Errors are unrecoverable.
     * @see <a href="https://github.com/Netflix/Hystrix/issues/713"></a> for more context
     * Solution taken from <a href="https://github.com/ReactiveX/RxJava/issues/748"></a>
     *
     * The specific set of Error that are considered unrecoverable are:
     * <ul>
     * <li>{@code StackOverflowError}</li>
     * <li>{@code VirtualMachineError}</li>
     * <li>{@code ThreadDeath}</li>
     * <li>{@code LinkageError}</li>
     * </ul>
     *
     * @param t throwable to check
     * @return true iff the t was caused by a java.lang.Error that is unrecoverable
     */
    private boolean isUnrecoverable(Throwable t) {
        if (t != null && t.getCause() != null) {
            Throwable cause = t.getCause();
            if (cause instanceof StackOverflowError) {
                return true;
            } else if (cause instanceof VirtualMachineError) {
                return true;
            } else if (cause instanceof ThreadDeath) {
                return true;
            } else if (cause instanceof LinkageError) {
                return true;
            }
        }
        return false;
    }

    private boolean isRecoverableError(Throwable t) {
        if (t != null && t.getCause() != null) {
            Throwable cause = t.getCause();
            if (cause instanceof java.lang.Error) {
                return !isUnrecoverable(t);
            }
        }
        return false;
    }

    protected void handleThreadEnd(AbstractCommand<R> _cmd) {
//        HystrixCounters.decrementGlobalConcurrentThreads();
        threadPool.markThreadCompletion();
        try {
//            executionHook.onThreadComplete(_cmd);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onThreadComplete", hookEx);
        }
    }

    /**
     *
     * @return if onNext events should be reported on
     * This affects {@link HystrixRequestLog}, and {@link HystrixEventNotifier} currently.
     */
    protected boolean shouldOutputOnNextEvents() {
        return false;
    }

    private static void setRequestContextIfNeeded(final HystrixRequestContext currentRequestContext) {
        if (!HystrixRequestContext.isCurrentThreadInitialized()) {
            // even if the user Observable doesn't have context we want it set for chained operators
            HystrixRequestContext.setContextOnCurrentThread(currentRequestContext);
        }
    }

    /**
     * Get the TryableSemaphore this HystrixCommand should use if a fallback occurs.
     * 
     * @return TryableSemaphore
     */
    protected TryableSemaphore getFallbackSemaphore() {
        if (fallbackSemaphoreOverride == null) {
            TryableSemaphore _s = fallbackSemaphorePerCircuit.get(commandKey.name());
            if (_s == null) {
                // we didn't find one cache so setup
                fallbackSemaphorePerCircuit.putIfAbsent(commandKey.name(), new TryableSemaphoreActual(properties.fallbackIsolationSemaphoreMaxConcurrentRequests()));
                // assign whatever got set (this or another thread)
                return fallbackSemaphorePerCircuit.get(commandKey.name());
            } else {
                return _s;
            }
        } else {
            return fallbackSemaphoreOverride;
        }
    }

    /**
     * Get the TryableSemaphore this HystrixCommand should use for execution if not running in a separate thread.
     * 
     * @return TryableSemaphore
     */
    protected TryableSemaphore getExecutionSemaphore() {
        if (properties.executionIsolationStrategy().get() == ExecutionIsolationStrategy.SEMAPHORE) {
            if (executionSemaphoreOverride == null) {
                TryableSemaphore _s = executionSemaphorePerCircuit.get(commandKey.name());
                if (_s == null) {
                    // we didn't find one cache so setup
                    executionSemaphorePerCircuit.putIfAbsent(commandKey.name(), new TryableSemaphoreActual(properties.executionIsolationSemaphoreMaxConcurrentRequests()));
                    // assign whatever got set (this or another thread)
                    return executionSemaphorePerCircuit.get(commandKey.name());
                } else {
                    return _s;
                }
            } else {
                return executionSemaphoreOverride;
            }
        } else {
            // return NoOp implementation since we're not using SEMAPHORE isolation
            return TryableSemaphoreNoOp.DEFAULT;
        }
    }

    /**
     * Each concrete implementation of AbstractCommand should return the name of the fallback method as a String
     * This will be used to determine if the fallback "exists" for firing the onFallbackStart/onFallbackError hooks
     * @deprecated This functionality is replaced by {@link #isFallbackUserDefined}, which is less implementation-aware
     * @return method name of fallback
     */
    @Deprecated
    protected abstract String getFallbackMethodName();

    protected abstract boolean isFallbackUserDefined();

    /**
     * @return {@link HyperionCommandGroupKey} used to group together multiple {@link AbstractCommand} objects.
     *         <p>
     *         The {@link HyperionCommandGroupKey} is used to represent a common relationship between commands. For example, a library or team name, the system all related commands interace with,
     *         common business purpose etc.
     */
    public HyperionCommandGroupKey getCommandGroup() {
        return commandGroup;
    }

    /**
     * @return {@link HyperionCommandKey} identifying this command instance for statistics, circuit-breaker, properties, etc.
     */
    public HyperionCommandKey getCommandKey() {
        return commandKey;
    }

    /**
     * @return {@link HyperionThreadPoolKey} identifying which thread-pool this command uses (when configured to run on separate threads via
     *         {@link HystrixCommandProperties#executionIsolationStrategy()}).
     */
//    public HystrixThreadPoolKey getThreadPoolKey() {
//        return threadPoolKey;
//    }

    /* package */HyperionCircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * The {@link HystrixCommandMetrics} associated with this {@link AbstractCommand} instance.
     *
     * @return HystrixCommandMetrics
     */
    public HyperionCommandMetrics getMetrics() {
        return metrics;
    }

    /**
     * The {@link HystrixCommandProperties} associated with this {@link AbstractCommand} instance.
     * 
     * @return HystrixCommandProperties
     */
    public HyperionCommandProperties getProperties() {
        return properties;
    }

    /* ******************************************************************************** */
    /* ******************************************************************************** */
    /* Operators that implement hook application */
    /* ******************************************************************************** */
    /* ******************************************************************************** */


    private Exception wrapWithOnErrorHook(FailureType failureType, Throwable t) {
        Exception e = getExceptionFromThrowable(t);
        try {
//            return executionHook.onError(this, failureType, e);
            return e;
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onError", hookEx);
            return e;
        }
    }


    /**
     * Take an Exception and determine whether to throw it, its cause or a new HyperionRuntimeException.
     * <p>
     * This will only throw an HyperionRuntimeException, HyperionBadRequestException, IllegalStateException
     * or any exception that implements ExceptionNotWrappedByHyperion.
     * 
     * @param e initial exception
     * @return HyperionRuntimeException, HyperionBadRequestException or IllegalStateException
     */
    protected Throwable decomposeException(Exception e) {
        if (e instanceof IllegalStateException) {
            return (IllegalStateException) e;
        }
        if (e instanceof HyperionBadRequestException) {
            if (shouldNotBeWrapped(e.getCause())) {
                return e.getCause();
            }
            return (HyperionBadRequestException) e;
        }
        if (e.getCause() instanceof HyperionBadRequestException) {
            if(shouldNotBeWrapped(e.getCause().getCause())) {
                return e.getCause().getCause();
            }
            return (HyperionBadRequestException) e.getCause();
        }
        if (e instanceof HyperionRuntimeException) {
            return (HyperionRuntimeException) e;
        }
        // if we have an exception we know about we'll throw it directly without the wrapper exception
        if (e.getCause() instanceof HyperionRuntimeException) {
            return (HyperionRuntimeException) e.getCause();
        }
        if (shouldNotBeWrapped(e)) {
            return e;
        }
        if (shouldNotBeWrapped(e.getCause())) {
            return e.getCause();
        }
        // we don't know what kind of exception this is so create a generic message and throw a new HyperionRuntimeException
        String message = getLogMessagePrefix() + " failed while executing.";
        logger.debug(message, e); // debug only since we're throwing the exception and someone higher will do something with it
        return new HyperionRuntimeException(FailureType.COMMAND_EXCEPTION, this.getClass(), message, e, null);

    }

    /* ******************************************************************************** */
    /* ******************************************************************************** */
    /* TryableSemaphore */
    /* ******************************************************************************** */
    /* ******************************************************************************** */

    /**
     * Semaphore that only supports tryAcquire and never blocks and that supports a dynamic permit count.
     * <p>
     * Using AtomicInteger increment/decrement instead of java.util.concurrent.Semaphore since we don't need blocking and need a custom implementation to get the dynamic permit count and since
     * AtomicInteger achieves the same behavior and performance without the more complex implementation of the actual Semaphore class using AbstractQueueSynchronizer.
     */
    /* package */static class TryableSemaphoreActual implements TryableSemaphore {
        protected final HystrixProperty<Integer> numberOfPermits;
        private final AtomicInteger count = new AtomicInteger(0);

        public TryableSemaphoreActual(HystrixProperty<Integer> numberOfPermits) {
            this.numberOfPermits = numberOfPermits;
        }

        @Override
        public boolean tryAcquire() {
            int currentCount = count.incrementAndGet();
            if (currentCount > numberOfPermits.get()) {
                count.decrementAndGet();
                return false;
            } else {
                return true;
            }
        }

        @Override
        public void release() {
            count.decrementAndGet();
        }

        @Override
        public int getNumberOfPermitsUsed() {
            return count.get();
        }

    }

    /* package */static class TryableSemaphoreNoOp implements TryableSemaphore {

        public static final TryableSemaphore DEFAULT = new TryableSemaphoreNoOp();

        @Override
        public boolean tryAcquire() {
            return true;
        }

        @Override
        public void release() {

        }

        @Override
        public int getNumberOfPermitsUsed() {
            return 0;
        }

    }

    /* package */static interface TryableSemaphore {

        /**
         * Use like this:
         * <p>
         * 
         * <pre>
         * if (s.tryAcquire()) {
         * try {
         * // do work that is protected by 's'
         * } finally {
         * s.release();
         * }
         * }
         * </pre>
         * 
         * @return boolean
         */
        public abstract boolean tryAcquire();

        /**
         * ONLY call release if tryAcquire returned true.
         * <p>
         * 
         * <pre>
         * if (s.tryAcquire()) {
         * try {
         * // do work that is protected by 's'
         * } finally {
         * s.release();
         * }
         * }
         * </pre>
         */
        public abstract void release();

        public abstract int getNumberOfPermitsUsed();

    }

    /* ******************************************************************************** */
    /* ******************************************************************************** */
    /* RequestCache */
    /* ******************************************************************************** */
    /* ******************************************************************************** */

    /**
     * Key to be used for request caching.
     * <p>
     * By default this returns null which means "do not cache".
     * <p>
     * To enable caching override this method and return a string key uniquely representing the state of a command instance.
     * <p>
     * If multiple command instances in the same request scope match keys then only the first will be executed and all others returned from cache.
     * 
     * @return cacheKey
     */
    protected String getCacheKey() {
        return null;
    }

    public String getPublicCacheKey() {
        return getCacheKey();
    }

    protected boolean isRequestCachingEnabled() {
        return properties.requestCacheEnabled().get() && getCacheKey() != null;
    }

    protected String getLogMessagePrefix() {
        return getCommandKey().name();
    }

    /**
     * Whether the 'circuit-breaker' is open meaning that <code>execute()</code> will immediately return
     * the <code>getFallback()</code> response and not attempt a HystrixCommand execution.
     *
     * 4 columns are ForcedOpen | ForcedClosed | CircuitBreaker open due to health ||| Expected Result
     *
     * T | T | T ||| OPEN (true)
     * T | T | F ||| OPEN (true)
     * T | F | T ||| OPEN (true)
     * T | F | F ||| OPEN (true)
     * F | T | T ||| CLOSED (false)
     * F | T | F ||| CLOSED (false)
     * F | F | T ||| OPEN (true)
     * F | F | F ||| CLOSED (false)
     *
     * @return boolean
     */
    public boolean isCircuitBreakerOpen() {
        return properties.circuitBreakerForceOpen().get() || (!properties.circuitBreakerForceClosed().get() && circuitBreaker.isOpen());
    }

    /**
     * If this command has completed execution either successfully, via fallback or failure.
     * 
     * @return boolean
     */
    public boolean isExecutionComplete() {
        return commandState.get() == CommandState.TERMINAL;
    }

    /**
     * Whether the execution occurred in a separate thread.
     * <p>
     * This should be called only once execute()/queue()/fireOrForget() are called otherwise it will always return false.
     * <p>
     * This specifies if a thread execution actually occurred, not just if it is configured to be executed in a thread.
     * 
     * @return boolean
     */
    public boolean isExecutedInThread() {
        return getCommandResult().isExecutedInThread();
    }

    /**
     * Whether the response was returned successfully either by executing <code>run()</code> or from cache.
     * 
     * @return boolean
     */
    public boolean isSuccessfulExecution() {
        return getCommandResult().getEventCounts().contains(HyperionEventType.SUCCESS);
    }

    /**
     * Whether the <code>run()</code> resulted in a failure (exception).
     * 
     * @return boolean
     */
    public boolean isFailedExecution() {
        return getCommandResult().getEventCounts().contains(HyperionEventType.FAILURE);
    }

    /**
     * Get the Throwable/Exception thrown that caused the failure.
     * <p>
     * If <code>isFailedExecution() == true</code> then this would represent the Exception thrown by the <code>run()</code> method.
     * <p>
     * If <code>isFailedExecution() == false</code> then this would return null.
     * 
     * @return Throwable or null
     */
    public Throwable getFailedExecutionException() {
        return executionResult.getException();
    }

    /**
     * Get the Throwable/Exception emitted by this command instance prior to checking the fallback.
     * This exception instance may have been generated via a number of mechanisms:
     * 1) failed execution (in this case, same result as {@link #getFailedExecutionException()}.
     * 2) timeout
     * 3) short-circuit
     * 4) rejection
     * 5) bad request
     *
     * If the command execution was successful, then this exception instance is null (there was no exception)
     *
     * Note that the caller of the command may not receive this exception, as fallbacks may be served as a response to
     * the exception.
     *
     * @return Throwable or null
     */
    public Throwable getExecutionException() {
        return executionResult.getExecutionException();
    }

    /**
     * Whether the response received from was the result of some type of failure
     * and <code>getFallback()</code> being called.
     * 
     * @return boolean
     */
    public boolean isResponseFromFallback() {
        return getCommandResult().getEventCounts().contains(HyperionEventType.FALLBACK_SUCCESS);
    }

    /**
     * Whether the response received was the result of a timeout
     * and <code>getFallback()</code> being called.
     * 
     * @return boolean
     */
    public boolean isResponseTimedOut() {
        return getCommandResult().getEventCounts().contains(HyperionEventType.TIMEOUT);
    }

    /**
     * Whether the response received was a fallback as result of being
     * short-circuited (meaning <code>isCircuitBreakerOpen() == true</code>) and <code>getFallback()</code> being called.
     * 
     * @return boolean
     */
    public boolean isResponseShortCircuited() {
        return getCommandResult().getEventCounts().contains(HyperionEventType.SHORT_CIRCUITED);
    }

    /**
     * Whether the response is from cache and <code>run()</code> was not invoked.
     * 
     * @return boolean
     */
    public boolean isResponseFromCache() {
        return isResponseFromCache;
    }

    /**
     * Whether the response received was a fallback as result of being rejected via sempahore
     *
     * @return boolean
     */
    public boolean isResponseSemaphoreRejected() {
        return getCommandResult().isResponseSemaphoreRejected();
    }

    /**
     * Whether the response received was a fallback as result of being rejected via threadpool
     *
     * @return boolean
     */
    public boolean isResponseThreadPoolRejected() {
        return getCommandResult().isResponseThreadPoolRejected();
    }

    /**
     * Whether the response received was a fallback as result of being rejected (either via threadpool or semaphore)
     *
     * @return boolean
     */
    public boolean isResponseRejected() {
        return getCommandResult().isResponseRejected();
    }

    /**
     * List of HystrixCommandEventType enums representing events that occurred during execution.
     * <p>
     * Examples of events are SUCCESS, FAILURE, TIMEOUT, and SHORT_CIRCUITED
     * 
     * @return {@code List<HyperionEventType>}
     */
    public List<HyperionEventType> getExecutionEvents() {
        return getCommandResult().getOrderedList();
    }

    private ExecutionResult getCommandResult() {
        ExecutionResult resultToReturn;
        if (executionResultAtTimeOfCancellation == null) {
            resultToReturn = executionResult;
        } else {
            resultToReturn = executionResultAtTimeOfCancellation;
        }

        if (isResponseFromCache) {
            resultToReturn = resultToReturn.addEvent(HyperionEventType.RESPONSE_FROM_CACHE);
        }

        return resultToReturn;
    }

    /**
     * Number of emissions of the execution of a command.  Only interesting in the streaming case.
     * @return number of <code>OnNext</code> emissions by a streaming command
     */
    @Override
    public int getNumberEmissions() {
        return getCommandResult().getEventCounts().getCount(HyperionEventType.EMIT);
    }

    /**
     * Number of emissions of the execution of a fallback.  Only interesting in the streaming case.
     * @return number of <code>OnNext</code> emissions by a streaming fallback
     */
    @Override
    public int getNumberFallbackEmissions() {
        return getCommandResult().getEventCounts().getCount(HyperionEventType.FALLBACK_EMIT);
    }

    @Override
    public int getNumberCollapsed() {
        return getCommandResult().getEventCounts().getCount(HyperionEventType.COLLAPSED);
    }

//    @Override
//    public HystrixCollapserKey getOriginatingCollapserKey() {
//        return executionResult.getCollapserKey();
//    }

    /**
     * The execution time of this command instance in milliseconds, or -1 if not executed.
     * 
     * @return int
     */
    public int getExecutionTimeInMilliseconds() {
        return getCommandResult().getExecutionLatency();
    }

    /**
     * Time in Nanos when this command instance's run method was called, or -1 if not executed 
     * for e.g., command threw an exception
      *
      * @return long
     */
    public long getCommandRunStartTimeInNanos() {
        return executionResult.getCommandRunStartTimeInNanos();
    }

    @Override
    public ExecutionResult.EventCounts getEventCounts() {
        return getCommandResult().getEventCounts();
    }

    protected Exception getExceptionFromThrowable(Throwable t) {
        Exception e;
        if (t instanceof Exception) {
            e = (Exception) t;
        } else {
            // Hystrix 1.x uses Exception, not Throwable so to prevent a breaking change Throwable will be wrapped in Exception
            e = new Exception("Throwable caught while executing.", t);
        }
        return e;
    }

}
