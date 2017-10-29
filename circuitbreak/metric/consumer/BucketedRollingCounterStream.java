/**
 * Copyright 2015 Netflix, Inc.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pingan.testdemo.circuitbreak.metric.consumer;

import com.netflix.hystrix.metric.HystrixEvent;
import com.pingan.testdemo.circuitbreak.HyperionCommandMetrics.HealthCounts;
import com.pingan.testdemo.circuitbreak.metric.HyperionEvent;
import com.pingan.testdemo.circuitbreak.metric.HyperionEventStream;

import rx.Observable;
import rx.functions.Action0;
import rx.functions.Func1;
import rx.functions.Func2;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Refinement of {@link BucketedCounterStream} which reduces numBuckets at a time.
 *
 * @param <Event> type of raw data that needs to get summarized into a bucket
 * @param <Bucket> type of data contained in each bucket
 * @param <Output> type of data emitted to stream subscribers (often is the same as A but does not have to be)
 */
public abstract class BucketedRollingCounterStream<Event extends HyperionEvent, Bucket, Output> extends BucketedCounterStream<Event, Bucket, Output> {
    private Observable<Output> sourceStream;
    private final AtomicBoolean isSourceCurrentlySubscribed = new AtomicBoolean(false);

    protected BucketedRollingCounterStream(HyperionEventStream<Event> stream, final int numBuckets, int bucketSizeInMs,
                                           final Func2<Bucket, Event, Bucket> appendRawEventToBucket,
                                           final Func2<Output, Bucket, Output> reduceBucket) {
    	/**
    	 * appendRawEventToBucket：将bucketSizeInMs时间内所有请求的结果类型（成功、失败等）累加到一个long数组中，并发射给reduceBucket
    	 * reduceBucket：将appendRawEventToBucket发射的long数组加起来，计算成功率
    	 */
        super(stream, numBuckets, bucketSizeInMs, appendRawEventToBucket);
        Func1<Observable<Bucket>, Observable<Output>> reduceWindowToSummary = new Func1<Observable<Bucket>, Observable<Output>>() {
            @Override
            public Observable<Output> call(Observable<Bucket> window) {
//            	System.out.println(Thread.currentThread().getName() + ": ---------" + getEmptyOutputValue());
//            	System.out.println(Thread.currentThread().getName() + ": BucketedRollingCounterStream---------" + numBuckets);
                return window.scan(getEmptyOutputValue(), reduceBucket).skip(numBuckets);
            }
        };
        this.sourceStream = bucketedStream      //stream broken up into buckets
                .window(numBuckets, 1)          //在BucketedCounterStream中用startWith发射了一个大小为numBuckets的空list，所以此处window会一直发射大小为numBuckets的list；如果没有startWith，会导致window一开始发射大小<numBuckets的list，直到执行numBuckets次后才会稳定发射大小为numBuckets的list，emit overlapping windows of buckets
                .flatMap(reduceWindowToSummary) //convert a window of bucket-summaries into a single summary
                .doOnSubscribe(new Action0() {
                    @Override
                    public void call() {
//                    	System.out.println("lalilalilali");
                        isSourceCurrentlySubscribed.set(true);
                    }
                })
                .doOnUnsubscribe(new Action0() {
                    @Override
                    public void call() {
                        isSourceCurrentlySubscribed.set(false);
                    }
                })
                .share()                        //multiple subscribers should get same data
                .onBackpressureDrop();          //if there are slow consumers, data should not buffer
    }

    @Override
    public Observable<Output> observe() {
        return sourceStream;
    }

    /* package-private */ boolean isSourceCurrentlySubscribed() {
        return isSourceCurrentlySubscribed.get();
    }
}
