package dtm.di.exceptions.boot;

public class InvalidBootException extends RuntimeException {

    public InvalidBootException(String message){
        super(message);
    }

    public InvalidBootException(String message, Throwable th){
        super(message, th);
    }

}
