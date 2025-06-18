package dtm.di.core;

public interface ExceptionHandlerInvoker {
    void invoke(Thread thread, Throwable throwable) throws Exception;
}
