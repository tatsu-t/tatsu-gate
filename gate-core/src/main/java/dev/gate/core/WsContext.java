package dev.gate.core;

import org.eclipse.jetty.websocket.api.Session;
import java.io.IOException;

public class WsContext {

    private static final Logger logger = new Logger(WsContext.class);

    private final Session session;

    public WsContext(Session session) {
        this.session = session;
    }

    public void send(String message) {
        try {
            session.getRemote().sendString(message);
        } catch (IOException e) {
            logger.error("Failed to send WebSocket message: " + e.getMessage(), e);
        }
    }

    public boolean isOpen() {
        return session.isOpen();
    }
}