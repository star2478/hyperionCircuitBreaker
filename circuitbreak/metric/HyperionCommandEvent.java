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
package com.pingan.testdemo.circuitbreak.metric;


import com.pingan.testdemo.circuitbreak.HyperionCommandKey;
import com.pingan.testdemo.circuitbreak.HyperionThreadPoolKey;

import rx.functions.Func1;

/**
 * Data class that comprises the event stream for Hystrix command executions.
 * As of 1.5.0-RC1, this is only {@link HystrixCommandCompletion}s.
 */
public abstract class HyperionCommandEvent implements HyperionEvent {
    private final HyperionCommandKey commandKey;
    private final HyperionThreadPoolKey threadPoolKey;

    protected HyperionCommandEvent(HyperionCommandKey commandKey, HyperionThreadPoolKey threadPoolKey) {
        this.commandKey = commandKey;
        this.threadPoolKey = threadPoolKey;
    }

    public HyperionCommandKey getCommandKey() {
        return commandKey;
    }

    public HyperionThreadPoolKey getThreadPoolKey() {
        return threadPoolKey;
    }

    public abstract boolean isExecutionStart();

    public abstract boolean isExecutedInThread();

    public abstract boolean isResponseThreadPoolRejected();

    public abstract boolean isCommandCompletion();

    public abstract boolean didCommandExecute();

    public static final Func1<HyperionCommandEvent, Boolean> filterCompletionsOnly = new Func1<HyperionCommandEvent, Boolean>() {
        @Override
        public Boolean call(HyperionCommandEvent commandEvent) {
            return commandEvent.isCommandCompletion();
        }
    };

    public static final Func1<HyperionCommandEvent, Boolean> filterActualExecutions = new Func1<HyperionCommandEvent, Boolean>() {
        @Override
        public Boolean call(HyperionCommandEvent commandEvent) {
            return commandEvent.didCommandExecute();
        }
    };
}
