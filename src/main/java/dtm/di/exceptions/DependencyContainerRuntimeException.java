package dtm.di.exceptions;

public class DependencyContainerRuntimeException extends RuntimeException{

    public DependencyContainerRuntimeException(String message){
        super(message);
    }

    public DependencyContainerRuntimeException(Throwable cause) {
        super(cause);
    }

    public DependencyContainerRuntimeException(String message, Throwable th){
        super(message, th);
    }

    public DependencyContainerRuntimeException(String message, Throwable cause,
                                        boolean enableSuppression,
                                        boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
