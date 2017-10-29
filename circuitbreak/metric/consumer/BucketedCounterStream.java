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
import com.netflix.hystrix.metric.HystrixEventStream;
import com.pingan.testdemo.circuitbreak.metric.HyperionEvent;
import com.pingan.testdemo.circuitbreak.metric.HyperionEventStream;

import rx.Observable;
import rx.Subscription;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.subjects.BehaviorSubject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Abstract class that imposes a bucketing structure and provides streams of buckets
 *
 * @param <Event> type of raw data that needs to get summarized into a bucket
 * @param <Bucket> type of data contained in each bucket
 * @param <Output> type of data emitted to stream subscribers (often is the same as A but does not have to be)
 */
public abstract class BucketedCounterStream<Event extends HyperionEvent, Bucket, Output> {
    protected final int numBuckets;
    protected final Observable<Bucket> bucketedStream;
    protected final AtomicReference<Subscription> subscription = new AtomicReference<Subscription>(null);

    private final Func1<Observable<Event>, Observable<Bucket>> reduceBucketToSummary;

    private final BehaviorSubject<Output> counterSubject = BehaviorSubject.create(getEmptyOutputValue());

    protected BucketedCounterStream(final HyperionEventStream<Event> inputEventStream, final int numBuckets, final int bucketSizeInMs,
                                    final Func2<Bucket, Event, Bucket> appendRawEventToBucket) {
        this.numBuckets = numBuckets;
        this.reduceBucketToSummary = new Func1<Observable<Event>, Observable<Bucket>>() {
            @Override
            public Observable<Bucket> call(Observable<Event> eventBucket) {
            	/**
            	 * 每次请求结束，都会调用HyperionCommandCompletionStream.write()->Subject.onNext(HyperionCommandCompletion)->本方法，此处的appendRawEventToBucket是HyperionCommandMetrics.appendEventToBucket
            	 * 
            	 * 由于bucketedStream设置了window(bucketSizeInMs, TimeUnit.MILLISECONDS)，所以每隔bucketSizeInMs（比如500ms），这里就会被执行一次。
            	 * 在bucketSizeInMs时间内，请求发生了多少次，就意味着HyperionCommandCompletionStream.write()被调用多少次，进而就有多少个HyperionCommandCompletion被传过来进行reduce()计算
            	 * reduce()的初始值是一个由getEmptyBucketSummary()返回的空long数组，经过reduce()计算，bucketSizeInMs时间内所有请求的结果类型（成功、失败等）累加到一个long数组中
				 * 当bucketSizeInMs时间一到，就将long数组发射出去
            	 * 注意：如果在bucketSizeInMs时间内没有一次请求，将会发射一个空long数组（由getEmptyBucketSummary()返回）
            	 */
//            	System.out.println("======================BucketedCounterStream.call()");
                return eventBucket.reduce(getEmptyBucketSummary(), appendRawEventToBucket);
            }
        };

        final List<Bucket> emptyEventCountsToStart = new ArrayList<Bucket>();
        for (int i = 0; i < numBuckets; i++) {
            emptyEventCountsToStart.add(getEmptyBucketSummary());
        }
        // System.out.println("nihaohahahah=======" + bucketSizeInMs);
        // bucketSizeInMs=500, numBuckets=10
        this.bucketedStream = Observable.defer(new Func0<Observable<Bucket>>() {
            @Override
            public Observable<Bucket> call() {
                return inputEventStream
                        .observe()
                        .window(bucketSizeInMs, TimeUnit.MILLISECONDS) // 每隔多长时间触发订阅者，bucket it by the counter window so we can emit to the next operator in time chunks, not on every OnNext
                        .flatMap(reduceBucketToSummary)                //for a given bucket, turn it into a long array containing counts of event types
                        .startWith(emptyEventCountsToStart);           // 为了使window一直处于满填充，一开始就要先发一个window大小的空list过去，start it with empty arrays to make consumer logic as generic as possible (windows are always full)
            }
        });
    }

    abstract Bucket getEmptyBucketSummary();

    abstract Output getEmptyOutputValue();

    /**
     * Return the stream of buckets
     * @return stream of buckets
     */
    public abstract Observable<Output> observe();

    public void startCachingStreamValuesIfUnstarted() {
        if (subscription.get() == null) {
            //the stream is not yet started
            Subscription candidateSubscription = observe().subscribe(counterSubject);
//            System.out.println("=----------wodefuck" + candidateSubscription);
            if (subscription.compareAndSet(null, candidateSubscription)) {
                //won the race to set the subscription
            } else {
                //lost the race to set the subscription, so we need to cancel this one
                candidateSubscription.unsubscribe();
            }
        }
    }

    /**
     * Synchronous call to retrieve the last calculated bucket without waiting for any emissions
     * @return last calculated bucket
     */
    public Output getLatest() {
        startCachingStreamValuesIfUnstarted();
        if (counterSubject.hasValue()) {
            return counterSubject.getValue();
        } else {
            return getEmptyOutputValue();
        }
    }

    public void unsubscribe() {
        Subscription s = subscription.get();
        if (s != null) {
            s.unsubscribe();
            subscription.compareAndSet(s, null);
        }
    }
}
