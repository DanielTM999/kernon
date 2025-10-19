package dtm.di.storage.handler;

import dtm.di.annotations.HandleException;
import dtm.di.core.ExceptionHandlerInvoker;
import dtm.di.prototypes.ProxyObject;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.InvalidParameterException;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

@Slf4j
public class ControllerAdviceHandlerInvokeService implements ExceptionHandlerInvoker {

    private final Map<Class<? extends Throwable>, Method> throwableMethodMap;
    private final Object controllerAdviceUserInstance;
    private final ExceptionHandlerInvoker defaultHandlerInvoker;

    public ControllerAdviceHandlerInvokeService(Object controllerAdviceUserInstance, ExceptionHandlerInvoker defaultHandlerInvoker) {
        this.controllerAdviceUserInstance = (controllerAdviceUserInstance instanceof ProxyObject proxyObject) ? proxyObject.getRealInstance() : controllerAdviceUserInstance;
        this.defaultHandlerInvoker = defaultHandlerInvoker;
        this.throwableMethodMap = new ConcurrentHashMap<>();
        initExceptionHandlers();
    }

    @Override
    public void invoke(Thread thread, Throwable throwable) throws Exception {
        Throwable realThrowable = unwrapThrowable(throwable);
        Class<?> throwableClass = realThrowable.getClass();
        Method handlerMethod = throwableMethodMap.get(throwableClass);

        if (handlerMethod == null) {
            handlerMethod = findCompatibleHandler(throwableClass);
        }

        if (handlerMethod != null) {
            try {
                handlerMethod.setAccessible(true);
                Object[] args = resolveMethodArguments(handlerMethod, thread, realThrowable);
                handlerMethod.invoke(controllerAdviceUserInstance, args);
                return;
            } catch (Exception e) {
                log.error("Erro ao invocar handler de exceção em ControllerAdvice", e);
                throw new Exception("Erro ao invocar handler de exceção em ControllerAdvice", e);
            }
        }

        if (defaultHandlerInvoker != null) {
            defaultHandlerInvoker.invoke(thread, throwable);
        } else {
            log.error("Erro não tratado em ControllerAdvice", throwable);
            throw new Exception("Erro não tratado em ControllerAdvice", throwable);
        }
    }

    private Method findCompatibleHandler(Class<?> throwableClass) {
        for (Map.Entry<Class<? extends Throwable>, Method> entry : throwableMethodMap.entrySet()) {
            if (entry.getKey().isAssignableFrom(throwableClass)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void initExceptionHandlers() {
        Method[] methods = controllerAdviceUserInstance.getClass().getDeclaredMethods();

        for (Method method : methods) {
            if (method.isAnnotationPresent(HandleException.class)) {
                HandleException annotation = method.getAnnotation(HandleException.class);
                Class<? extends Throwable>[] classList = annotation.value();
                if(classList != null){
                    for (Class<? extends Throwable> exClass : annotation.value()) {
                        throwableMethodMap.put(exClass, method);
                    }
                }
            }
        }
    }

    private Throwable unwrapThrowable(Throwable throwable) {
        Throwable t = throwable;
        while (true) {
            if (
                    t instanceof ExecutionException
                    || t instanceof InvocationTargetException
                    || t instanceof RuntimeException
            ) {

                Throwable cause = (t instanceof InvocationTargetException targetException)
                        ? targetException.getTargetException()
                        : t.getCause();

                if (cause != null && cause != t) {
                    t = cause;
                    continue;
                }
            }
            break;
        }
        return t;
    }

    private Object[] resolveMethodArguments(Method method, Thread thread, Throwable throwable) {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] args = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            if (Throwable.class.isAssignableFrom(paramTypes[i])) {
                args[i] = throwable;
            } else if (Thread.class.isAssignableFrom(paramTypes[i])) {
                args[i] = thread;
            } else {
                throw new InvalidParameterException("Parâmetro inválido no método: " + method.getName());
            }
        }

        return args;
    }

}
