package dev.gate.core;

import org.slf4j.LoggerFactory;

public class Logger {
    private final org.slf4j.Logger logger;

    public Logger(Class<?> clazz) {
        this.logger = LoggerFactory.getLogger(clazz);
    }

    public void info(String message) {
        logger.info(message);
    }

    public void warn(String message) {
        logger.warn(message);
    }

    public void error(String message) {
        logger.error(message);
    }

    public void error(String message, Throwable e) {
        logger.error(message, e);
    }
}