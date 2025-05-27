package dtm.di.prototypes.proxy;

import dtm.di.aop.AopProxyUtils;
import dtm.di.core.DependencyContainer;
import dtm.di.core.aop.AopUtils;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.This;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

public class ObjectInterceptor {
    private final Object delegate;
    private final DependencyContainer dependencyContainer;

    ObjectInterceptor(Object delegate, DependencyContainer dependencyContainer){
        this.dependencyContainer = dependencyContainer;
        this.delegate = delegate;
    }

    @RuntimeType
    public Object intercept(@Origin Method method, @AllArguments Object[] args, @This Object proxy) throws Throwable  {
        AopUtils aopUtils = AopProxyUtils.getInstance(dependencyContainer);
        aopUtils.applyBefore(method, args, proxy);
        try {
            Object result = method.invoke(delegate, args);
            return aopUtils.applyAfter(method, args, proxy, result);
        }catch (InvocationTargetException invocationTargetException){
            Throwable cause = invocationTargetException.getCause();
            if (cause == null) {
                cause = invocationTargetException.getTargetException();
            }
            while (cause instanceof InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            throw cause;
        }

    }


}
