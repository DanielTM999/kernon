package dtm.di.exceptions;

public class LazyDependencyException extends DependencyContainerException {
    public LazyDependencyException(String message) {
        super(message);
    }
}