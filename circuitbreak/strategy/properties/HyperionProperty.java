/**
 * Copyright 2012 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pingan.testdemo.circuitbreak.strategy.properties;

import com.netflix.hystrix.strategy.properties.HystrixPropertiesChainedArchaiusProperty.DynamicBooleanProperty;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesChainedArchaiusProperty.DynamicIntegerProperty;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesChainedArchaiusProperty.DynamicLongProperty;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesChainedArchaiusProperty.DynamicStringProperty;

/**
 * Generic interface to represent a property value so Hystrix can consume properties without being tied to any particular backing implementation.
 * 
 * @param <T>
 *            Type of property value
 */
public interface HyperionProperty<T> {

    public T get();

    /**
     * Helper methods for wrapping static values and dynamic Archaius (https://github.com/Netflix/archaius) properties in the {@link HyperionProperty} interface.
     */
    public static class Factory {

        public static <T> HyperionProperty<T> asProperty(final T value) {
            return new HyperionProperty<T>() {

                @Override
                public T get() {
                    return value;
                }

            };
        }

        /**
         * @ExcludeFromJavadoc
         */
        public static HyperionProperty<Integer> asProperty(final DynamicIntegerProperty value) {
            return new HyperionProperty<Integer>() {

                @Override
                public Integer get() {
                    return value.get();
                }

            };
        }

        /**
         * @ExcludeFromJavadoc
         */
        public static HyperionProperty<Long> asProperty(final DynamicLongProperty value) {
            return new HyperionProperty<Long>() {

                @Override
                public Long get() {
                    return value.get();
                }

            };
        }

        /**
         * @ExcludeFromJavadoc
         */
        public static HyperionProperty<String> asProperty(final DynamicStringProperty value) {
            return new HyperionProperty<String>() {

                @Override
                public String get() {
                    return value.get();
                }

            };
        }

        /**
         * @ExcludeFromJavadoc
         */
        public static HyperionProperty<Boolean> asProperty(final DynamicBooleanProperty value) {
            return new HyperionProperty<Boolean>() {

                @Override
                public Boolean get() {
                    return value.get();
                }

            };
        }

        /**
         * When retrieved this will return the value from the given {@link HyperionProperty} or if that returns null then return the <code>defaultValue</code>.
         * 
         * @param value
         *            {@link HyperionProperty} of property value that can return null (meaning no value)
         * @param defaultValue
         *            value to be returned if value returns null
         * @return value or defaultValue if value returns null
         */
        public static <T> HyperionProperty<T> asProperty(final HyperionProperty<T> value, final T defaultValue) {
            return new HyperionProperty<T>() {

                @Override
                public T get() {
                    T v = value.get();
                    if (v == null) {
                        return defaultValue;
                    } else {
                        return v;
                    }
                }

            };
        }

        /**
         * When retrieved this will iterate over the contained {@link HyperionProperty} instances until a non-null value is found and return that.
         * 
         * @param values properties to iterate over
         * @return first non-null value or null if none found
         */
        public static <T> HyperionProperty<T> asProperty(final HyperionProperty<T>... values) {
            return new HyperionProperty<T>() {

                @Override
                public T get() {
                    for (HyperionProperty<T> v : values) {
                        // return the first one that doesn't return null
                        if (v.get() != null) {
                            return v.get();
                        }
                    }
                    return null;
                }

            };
        }

        /**
         * @ExcludeFromJavadoc
         */
        public static <T> HyperionProperty<T> asProperty(final HyperionPropertiesChainedArchaiusProperty.ChainLink<T> chainedProperty) {
            return new HyperionProperty<T>() {

                @Override
                public T get() {
                    return chainedProperty.get();
                }

            };
        }

        public static <T> HyperionProperty<T> nullProperty() {
            return new HyperionProperty<T>() {

                @Override
                public T get() {
                    return null;
                }

            };
        }

    }

}
