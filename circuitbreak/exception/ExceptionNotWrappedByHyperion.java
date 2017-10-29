package com.pingan.testdemo.circuitbreak.exception;

/**
 * Exceptions can implement this interface to prevent Hystrix from wrapping detected exceptions in a HystrixRuntimeException
 */
public interface ExceptionNotWrappedByHyperion {

}
