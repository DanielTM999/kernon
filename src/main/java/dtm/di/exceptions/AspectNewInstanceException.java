package dtm.di.exceptions;

public class AspectNewInstanceException extends NewInstanceException{

    public AspectNewInstanceException(String message, Class<?> referenceClass, Throwable th) {
        super(message, referenceClass, th);
    }

    public AspectNewInstanceException(String message, Class<?> referenceClass) {
        super(message, referenceClass);
    }

}
