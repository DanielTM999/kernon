package dtm.di.exceptions;

public class UnloadError extends RuntimeException {
    public UnloadError(String message) {
        super(message);
    }

    public UnloadError(String message, Throwable th) {
        super(message, th);
    }
}
