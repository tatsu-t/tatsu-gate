package dev.gate.modules.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Logback filter that drops {@code CancelledKeyException} noise. Jetty emits
 * these when clients disconnect mid-request (common behind CDNs and on mobile
 * networks); they are harmless but flood error logs at scale.
 *
 * <p>Register in {@code logback.xml} inside an appender:
 * <pre>
 * &lt;filter class="dev.gate.modules.logging.CancelledKeyExceptionFilter"/&gt;
 * </pre>
 */
public class CancelledKeyExceptionFilter extends Filter<ILoggingEvent> {

    private static final String CANCELLED_KEY_EXCEPTION = "java.nio.channels.CancelledKeyException";

    @Override
    public FilterReply decide(ILoggingEvent event) {
        IThrowableProxy proxy = event.getThrowableProxy();
        if (proxy != null && CANCELLED_KEY_EXCEPTION.equals(proxy.getClassName())) {
            return FilterReply.DENY;
        }
        String message = event.getFormattedMessage();
        if (message != null && message.contains("CancelledKeyException")) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }
}
