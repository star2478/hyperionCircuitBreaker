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



import com.netflix.hystrix.strategy.concurrency.HystrixRequestContext;
import com.pingan.testdemo.circuitbreak.ExecutionResult;
import com.pingan.testdemo.circuitbreak.HyperionCommandKey;
import com.pingan.testdemo.circuitbreak.HyperionEventType;
import com.pingan.testdemo.circuitbreak.HyperionThreadPoolKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Data class which gets fed into event stream when a command completes (with any of the outcomes in {@link HyperionEventType}).
 */
public class HyperionCommandCompletion extends HyperionCommandEvent {
    protected final ExecutionResult executionResult;
    protected final HystrixRequestContext requestContext;

    private final static HyperionEventType[] ALL_EVENT_TYPES = HyperionEventType.values();

    HyperionCommandCompletion(ExecutionResult executionResult, HyperionCommandKey commandKey,
                             HyperionThreadPoolKey threadPoolKey, HystrixRequestContext requestContext) {
        super(commandKey, threadPoolKey);
        this.executionResult = executionResult;
        this.requestContext = requestContext;
    }

    public static HyperionCommandCompletion from(ExecutionResult executionResult, HyperionCommandKey commandKey, HyperionThreadPoolKey threadPoolKey) {
        return from(executionResult, commandKey, threadPoolKey, HystrixRequestContext.getContextForCurrentThread());
    }

    public static HyperionCommandCompletion from(ExecutionResult executionResult, HyperionCommandKey commandKey, HyperionThreadPoolKey threadPoolKey, HystrixRequestContext requestContext) {
        return new HyperionCommandCompletion(executionResult, commandKey, threadPoolKey, requestContext);
    }

    @Override
    public boolean isResponseThreadPoolRejected() {
        return executionResult.isResponseThreadPoolRejected();
    }

    @Override
    public boolean isExecutionStart() {
        return false;
    }

    @Override
    public boolean isExecutedInThread() {
        return executionResult.isExecutedInThread();
    }

    @Override
    public boolean isCommandCompletion() {
        return true;
    }

    public HystrixRequestContext getRequestContext() {
        return this.requestContext;
    }

    public ExecutionResult.EventCounts getEventCounts() {
        return executionResult.getEventCounts();
    }

    public long getExecutionLatency() {
        return executionResult.getExecutionLatency();
    }

    public long getTotalLatency() {
        return executionResult.getUserThreadLatency();
    }

    @Override
    public boolean didCommandExecute() {
        return executionResult.executionOccurred();
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        List<HyperionEventType> foundEventTypes = new ArrayList<HyperionEventType>();

        sb.append(getCommandKey().name()).append("[");
        for (HyperionEventType eventType: ALL_EVENT_TYPES) {
            if (executionResult.getEventCounts().contains(eventType)) {
                foundEventTypes.add(eventType);
            }
        }
        int i = 0;
        for (HyperionEventType eventType: foundEventTypes) {
            sb.append(eventType.name());
            int eventCount = executionResult.getEventCounts().getCount(eventType);
            if (eventCount > 1) {
                sb.append("x").append(eventCount);

            }
            if (i < foundEventTypes.size() - 1) {
                sb.append(", ");
            }
            i++;
        }
        sb.append("][").append(getExecutionLatency()).append(" ms]");
        return sb.toString();
    }
}
