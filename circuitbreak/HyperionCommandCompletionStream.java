/**
 * Copyright 2015 Netflix, Inc.
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


import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.SerializedSubject;
import rx.subjects.Subject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.pingan.testdemo.circuitbreak.metric.HyperionCommandCompletion;
import com.pingan.testdemo.circuitbreak.metric.HyperionEventStream;

/**
 * Per-Command stream of {@link HyperionCommandCompletion}s.  This gets written to by {@link HyperionThreadEventStream}s.
 * Events are emitted synchronously in the same thread that performs the command execution.
 */
public class HyperionCommandCompletionStream implements HyperionEventStream<HyperionCommandCompletion> {
    private final HyperionCommandKey commandKey;

	private final Subject<HyperionCommandCompletion, HyperionCommandCompletion> writeOnlySubject;
    private final Observable<HyperionCommandCompletion> readOnlyStream;

    private static final ConcurrentMap<String, HyperionCommandCompletionStream> streams = new ConcurrentHashMap<String, HyperionCommandCompletionStream>();

    public static HyperionCommandCompletionStream getInstance(HyperionCommandKey commandKey) {
        HyperionCommandCompletionStream initialStream = streams.get(commandKey.name());
        if (initialStream != null) {
            return initialStream;
        } else {
            synchronized (HyperionCommandCompletionStream.class) {
                HyperionCommandCompletionStream existingStream = streams.get(commandKey.name());
                if (existingStream == null) {
                    HyperionCommandCompletionStream newStream = new HyperionCommandCompletionStream(commandKey);
                    streams.putIfAbsent(commandKey.name(), newStream);
                    return newStream;
                } else {
                    return existingStream;
                }
            }
        }
    }

    HyperionCommandCompletionStream(final HyperionCommandKey commandKey) {
        this.commandKey = commandKey;

        this.writeOnlySubject = new SerializedSubject<HyperionCommandCompletion, HyperionCommandCompletion>(PublishSubject.<HyperionCommandCompletion>create());
        this.readOnlyStream = writeOnlySubject.share();
    }

    public static void reset() {
        streams.clear();
    }

    public void write(HyperionCommandCompletion event) {
//    	System.out.println("kekekee-----" + event.toString());
        writeOnlySubject.onNext(event);
    }


    @Override
    public Observable<HyperionCommandCompletion> observe() {
        return readOnlyStream;
    }

    @Override
    public String toString() {
        return "HyperionCommandCompletionStream(" + commandKey.name() + ")";
    }
}
