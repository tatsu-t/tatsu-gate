package dev.gate.core;

@FunctionalInterface
public interface ErrorHandler {
    void handle(Context ctx, Exception e);
}