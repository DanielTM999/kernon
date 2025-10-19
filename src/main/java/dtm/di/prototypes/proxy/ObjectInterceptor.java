package dtm.di.prototypes.proxy;

import dtm.di.aop.AopProxyUtils;
import dtm.di.core.DependencyContainer;
import dtm.di.core.aop.AopUtils;
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
            Object result = realMethod.invoke(delegate, args);
            return aopUtils.applyAfter(realMethod, args, proxy, delegate, result);
        }catch (InvocationTargetException invocationTargetException){
            Throwable cause = invocationTargetException.getCause();
            if (cause == null) {
                cause = invocationTargetException.getTargetException();
            }
            while (cause instanceof InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            throw cause;
        }catch (RuntimeException runtimeException){
            Throwable cause = runtimeException.getCause();
            while (cause instanceof InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            throw cause;
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

}
