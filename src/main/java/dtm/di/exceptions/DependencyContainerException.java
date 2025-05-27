package dtm.di.exceptions;

public class DependencyContainerException extends RuntimeException{

    public DependencyContainerException(String message){
        super(message);
    }

    public DependencyContainerException(Throwable cause) {
        super(cause);
    }

    public DependencyContainerException(String message, Throwable th){
        super(message, th);
    }

    public DependencyContainerException(String message, Throwable cause,
                        boolean enableSuppression,
                        boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

}
