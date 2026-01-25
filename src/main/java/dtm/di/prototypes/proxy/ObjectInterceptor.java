package dtm.di.prototypes.proxy;

import dtm.di.aop.AopProxyUtils;
import dtm.di.core.DependencyContainer;
import dtm.di.core.aop.AopUtils;
import dtm.di.exceptions.AopMainMethodException;
import net.bytebuddy.implementation.bind.annotation.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.Callable;

public class ObjectInterceptor {
    private final Object delegate;
    private final DependencyContainer dependencyContainer;

    ObjectInterceptor(Object delegate, DependencyContainer dependencyContainer){
        this.dependencyContainer = dependencyContainer;
        this.delegate = delegate;
    }

    @RuntimeType
    public Object intercept(
            @Origin Method method,
            @AllArguments Object[] args,
            @This Object proxy
    ) throws Throwable  {
        final AopUtils aopUtils = AopProxyUtils.getInstance(dependencyContainer);
        final Method realMethod = getRealMethodFromDelegate(method);

        aopUtils.applyBefore(realMethod, args, proxy, delegate);
        try {
            Object result;
            try{
                result = realMethod.invoke(delegate, args);
            }catch (IllegalAccessException | InvocationTargetException reflectiveOperationException){
                throw new AopMainMethodException("Erro ao invocar main AOP Method", reflectiveOperationException);
            }
            return aopUtils.applyAfter(realMethod, args, proxy, delegate, result);
        }catch (AopMainMethodException aopMainMethodException){
            Throwable cause = extractRootError(aopMainMethodException.getCause());
            executeOnErrorOrThrow(aopUtils, cause, realMethod, args, proxy, delegate);
            throw cause;
        }catch (RuntimeException runtimeException){
            Throwable cause = runtimeException.getCause();
            throw extractRootError(cause);
        }

    }

    private Method getRealMethodFromDelegate(Method proxyMethod) {
        try {
            return delegate.getClass().getMethod(proxyMethod.getName(), proxyMethod.getParameterTypes());
        } catch (NoSuchMethodException e) {
            try {
                return delegate.getClass()
                        .getDeclaredMethod(proxyMethod.getName(), proxyMethod.getParameterTypes());
            } catch (NoSuchMethodException ex) {
                return proxyMethod;
            }
        }
    }

    private void executeOnErrorOrThrow(
            final AopUtils aopUtils,
            Throwable cause,
            final Method realMethod,
            final Object[] args,
            final Object proxy,
            final Object delegate
    ) throws Throwable{
        try{
            aopUtils.applyOnErrorMethod(realMethod, args, proxy, delegate, cause);
        }catch (RuntimeException runtimeException){
            Throwable error = runtimeException.getCause();
            throw extractRootError(error);
        }
    }

    private Throwable extractRootError(Throwable baseError){
        Throwable current = baseError;
        while (current instanceof InvocationTargetException ite && ite.getTargetException() != null) {
            current = ite.getTargetException();
        }

        return current;
    }

}
