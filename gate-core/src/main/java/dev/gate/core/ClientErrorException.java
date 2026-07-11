package dev.gate.core;

/**
 * Exception representing a client-caused error. The request loop in {@link Gate}
 * catches it and responds with the given 4xx status and a JSON error body,
 * without invoking the {@link ErrorHandler} (which is reserved for server-side
 * failures). Thrown by the framework itself during request parsing (e.g. 413 on
 * body-size overflow in {@link Context#body()}), and available to application
 * handlers as a shortcut for returning 4xx responses.
 */
public final class ClientErrorException extends RuntimeException {

    private final int status;

    public ClientErrorException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** The HTTP status code (4xx). */
    public int status() {
        return status;
    }
}
