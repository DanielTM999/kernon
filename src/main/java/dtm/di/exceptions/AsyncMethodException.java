package dtm.di.exceptions;

import lombok.Getter;

import java.lang.reflect.Method;

@Getter
public class AsyncMethodException extends IllegalStateException {

    private final Class<?> clazz;
    private final Method method;

    public AsyncMethodException(String message, Method method, Class<?> clazz) {
        super(message);
        this.method = method;
        this.clazz = clazz;
    }
}
