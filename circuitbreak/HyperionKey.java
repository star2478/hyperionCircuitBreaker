package com.pingan.testdemo.circuitbreak;

/**
 * Basic class for hystrix keys
 */
public interface HyperionKey {
    /**
     * The word 'name' is used instead of 'key' so that Enums can implement this interface and it work natively.
     *
     * @return String
     */
    String name();

    /**
     * Default implementation of the interface
     */
    abstract class HyperionKeyDefault implements HyperionKey {
        private final String name;

        public HyperionKeyDefault(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
