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
package com.pingan.testdemo.circuitbreak.strategy.properties;

import java.util.concurrent.ConcurrentHashMap;

import com.netflix.hystrix.HystrixCollapserKey;
import com.netflix.hystrix.HystrixCollapserProperties;
import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixThreadPool;
import com.netflix.hystrix.HystrixThreadPoolKey;
import com.netflix.hystrix.HystrixThreadPoolProperties;
import com.netflix.hystrix.strategy.HystrixPlugins;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesStrategy;
import com.pingan.testdemo.circuitbreak.HyperionCommandKey;
import com.pingan.testdemo.circuitbreak.HyperionCommandProperties;

/**
 * Factory for retrieving properties implementations.
 * <p>
 * This uses given {@link HystrixPropertiesStrategy} implementations to construct Properties instances and caches each instance according to the cache key provided.
 * 
 * @ExcludeFromJavadoc
 */
public class HyperionPropertiesFactory {

    /**
     * Clears all the defaults in the static property cache. This makes it possible for property defaults to not persist for
     * an entire JVM lifetime.  May be invoked directly, and also gets invoked by <code>Hystrix.reset()</code>
     */
    public static void reset() {
        commandProperties.clear();
        threadPoolProperties.clear();
        collapserProperties.clear();
    }

    // String is CommandKey.name() (we can't use CommandKey directly as we can't guarantee it implements hashcode/equals correctly)
    private static final ConcurrentHashMap<String, HyperionCommandProperties> commandProperties = new ConcurrentHashMap<String, HyperionCommandProperties>();

    /**
     * Get an instance of {@link HystrixCommandProperties} with the given factory {@link HystrixPropertiesStrategy} implementation for each {@link HystrixCommand} instance.
     * 
     * @param key
     *            Pass-thru to {@link HystrixPropertiesStrategy#getCommandProperties} implementation.
     * @param builder
     *            Pass-thru to {@link HystrixPropertiesStrategy#getCommandProperties} implementation.
     * @return {@link HystrixCommandProperties} instance
     */
    public static HyperionCommandProperties getCommandProperties(HyperionCommandKey key, HyperionCommandProperties.Setter builder) {
//        HystrixPropertiesStrategy hystrixPropertiesStrategy = HystrixPlugins.getInstance().getPropertiesStrategy();
//        String cacheKey = hystrixPropertiesStrategy.getCommandPropertiesCacheKey(key, builder);
        String cacheKey = key.name();
        if (cacheKey != null) {
            HyperionCommandProperties properties = commandProperties.get(cacheKey);
            if (properties != null) {
                return properties;
            } else {
                if (builder == null) {
                    builder = HyperionCommandProperties.Setter();
                }
                // create new instance
//                properties = hystrixPropertiesStrategy.getCommandProperties(key, builder);
                properties = new HyperionPropertiesCommandDefault(key, builder);
                // cache and return
                HyperionCommandProperties existing = commandProperties.putIfAbsent(cacheKey, properties);
                if (existing == null) {
                    return properties;
                } else {
                    return existing;
                }
            }
        } else {
            // no cacheKey so we generate it with caching
//            return hystrixPropertiesStrategy.getCommandProperties(key, builder);
            return null;/////////////////////
        }
    }

    // String is ThreadPoolKey.name() (we can't use ThreadPoolKey directly as we can't guarantee it implements hashcode/equals correctly)
    private static final ConcurrentHashMap<String, HystrixThreadPoolProperties> threadPoolProperties = new ConcurrentHashMap<String, HystrixThreadPoolProperties>();

    /**
     * Get an instance of {@link HystrixThreadPoolProperties} with the given factory {@link HystrixPropertiesStrategy} implementation for each {@link HystrixThreadPool} instance.
     * 
     * @param key
     *            Pass-thru to {@link HystrixPropertiesStrategy#getThreadPoolProperties} implementation.
     * @param builder
     *            Pass-thru to {@link HystrixPropertiesStrategy#getThreadPoolProperties} implementation.
     * @return {@link HystrixThreadPoolProperties} instance
     */
    public static HystrixThreadPoolProperties getThreadPoolProperties(HystrixThreadPoolKey key, HystrixThreadPoolProperties.Setter builder) {
        HystrixPropertiesStrategy hystrixPropertiesStrategy = HystrixPlugins.getInstance().getPropertiesStrategy();
        String cacheKey = hystrixPropertiesStrategy.getThreadPoolPropertiesCacheKey(key, builder);
        if (cacheKey != null) {
            HystrixThreadPoolProperties properties = threadPoolProperties.get(cacheKey);
            if (properties != null) {
                return properties;
            } else {
                if (builder == null) {
                    builder = HystrixThreadPoolProperties.Setter();
                }
                // create new instance
                properties = hystrixPropertiesStrategy.getThreadPoolProperties(key, builder);
                // cache and return
                HystrixThreadPoolProperties existing = threadPoolProperties.putIfAbsent(cacheKey, properties);
                if (existing == null) {
                    return properties;
                } else {
                    return existing;
                }
            }
        } else {
            // no cacheKey so we generate it with caching
            return hystrixPropertiesStrategy.getThreadPoolProperties(key, builder);
        }
    }

    // String is CollapserKey.name() (we can't use CollapserKey directly as we can't guarantee it implements hashcode/equals correctly)
    private static final ConcurrentHashMap<String, HystrixCollapserProperties> collapserProperties = new ConcurrentHashMap<String, HystrixCollapserProperties>();

    /**
     * Get an instance of {@link HystrixCollapserProperties} with the given factory {@link HystrixPropertiesStrategy} implementation for each {@link HystrixCollapserKey} instance.
     * 
     * @param key
     *            Pass-thru to {@link HystrixPropertiesStrategy#getCollapserProperties} implementation.
     * @param builder
     *            Pass-thru to {@link HystrixPropertiesStrategy#getCollapserProperties} implementation.
     * @return {@link HystrixCollapserProperties} instance
     */
    public static HystrixCollapserProperties getCollapserProperties(HystrixCollapserKey key, HystrixCollapserProperties.Setter builder) {
        HystrixPropertiesStrategy hystrixPropertiesStrategy = HystrixPlugins.getInstance().getPropertiesStrategy();
        String cacheKey = hystrixPropertiesStrategy.getCollapserPropertiesCacheKey(key, builder);
        if (cacheKey != null) {
            HystrixCollapserProperties properties = collapserProperties.get(cacheKey);
            if (properties != null) {
                return properties;
            } else {
                if (builder == null) {
                    builder = HystrixCollapserProperties.Setter();
                }
                // create new instance
                properties = hystrixPropertiesStrategy.getCollapserProperties(key, builder);
                // cache and return
                HystrixCollapserProperties existing = collapserProperties.putIfAbsent(cacheKey, properties);
                if (existing == null) {
                    return properties;
                } else {
                    return existing;
                }
            }
        } else {
            // no cacheKey so we generate it with caching
            return hystrixPropertiesStrategy.getCollapserProperties(key, builder);
        }
    }

}
