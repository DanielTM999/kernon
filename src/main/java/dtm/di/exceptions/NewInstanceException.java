package dtm.di.exceptions;

import lombok.Getter;

@Getter
public class NewInstanceException extends DependencyContainerException{
    private final Class<?> referenceClass;

    public NewInstanceException(String message, Class<?> referenceClass, Throwable th){
        super(message, th);
        this.referenceClass = referenceClass;
    }
    public NewInstanceException(String message, Class<?> referenceClass){
        super(message);
        this.referenceClass = referenceClass;
    }

}
