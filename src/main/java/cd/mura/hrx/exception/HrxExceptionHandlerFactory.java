package cd.mura.hrx.exception;

import jakarta.faces.context.ExceptionHandler;
import jakarta.faces.context.ExceptionHandlerFactory;

/**
 * Installs the application exception handler while preserving the handler
 * supplied by the JSF implementation.
 */
public final class HrxExceptionHandlerFactory extends ExceptionHandlerFactory {

    private final ExceptionHandlerFactory wrapped;

    public HrxExceptionHandlerFactory(ExceptionHandlerFactory wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public ExceptionHandler getExceptionHandler() {
        return new HrxExceptionHandler(wrapped.getExceptionHandler());
    }

    @Override
    public ExceptionHandlerFactory getWrapped() {
        return wrapped;
    }
}
