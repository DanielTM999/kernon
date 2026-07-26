package dtm.di.prototypes.proxy;

import dtm.di.aop.AopProxyUtils;
import dtm.di.core.DependencyContainer;
import dtm.di.core.aop.AopUtils;
import dtm.di.exceptions.AopMainMethodException;
import net.bytebuddy.implementation.bind.annotation.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

public class ObjectInterceptor {
    private final Object realInstance;
    private final DependencyContainer dependencyContainer;

    ObjectInterceptor(Object realInstance, DependencyContainer dependencyContainer){
        this.dependencyContainer = dependencyContainer;
        this.realInstance = realInstance;
    }

    @RuntimeType
    public Object intercept(
            @Origin Method method,
            @AllArguments Object[] args,
            @This Object proxy,
            @SuperCall Callable<?> zuper
    ) throws Throwable  {
        final AopUtils aopUtils = AopProxyUtils.getInstance(dependencyContainer);

        aopUtils.applyBefore(method, args, proxy, realInstance);
        try {
            Object result;
            try{
                AopUtils.MainMethodResult mainMethodResult = aopUtils.applyOnMainMethod(method, args, proxy, realInstance, zuper);
                result = mainMethodResult.intercepted() ? mainMethodResult.result() : zuper.call();
            }catch (RuntimeException | Error directThrow){
                throw directThrow;
            }catch (InvocationTargetException invocationTargetException){
                throw new AopMainMethodException("Erro ao invocar main AOP Method", invocationTargetException);
            }catch (Throwable reflectiveOperationException){
                throw new AopMainMethodException("Erro ao invocar main AOP Method", reflectiveOperationException);
            }
            return aopUtils.applyAfter(method, args, proxy, realInstance, result);
        }catch (AopMainMethodException aopMainMethodException){
            Throwable cause = extractRootError(aopMainMethodException.getCause());
            executeOnErrorOrThrow(aopUtils, cause, method, args, proxy, realInstance);
            throw cause;
        }catch (RuntimeException runtimeException){
            Throwable cause = (runtimeException.getCause() != null) ? runtimeException.getCause() : runtimeException;
            Throwable root = extractRootError(cause);
            executeOnErrorOrThrow(aopUtils, root, method, args, proxy, realInstance);
            throw root;
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
