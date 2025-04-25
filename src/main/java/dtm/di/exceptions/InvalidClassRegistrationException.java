package dtm.di.exceptions;

import lombok.Getter;

@Getter
public class InvalidClassRegistrationException extends DependencyContainerException{
    private final Class<?> referenceClass;

    public InvalidClassRegistrationException(String message, Class<?> referenceClass, Throwable th){
        super(message, th);
        this.referenceClass = referenceClass;
    }
    public InvalidClassRegistrationException(String message, Class<?> referenceClass){
        super(message);
        this.referenceClass = referenceClass;
    }
}
