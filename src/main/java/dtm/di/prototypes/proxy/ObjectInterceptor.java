package dtm.di.prototypes.proxy;

import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.This;

import java.lang.reflect.Method;

public class ObjectInterceptor {
    private final Object delegate;

    ObjectInterceptor(Object delegate){
        this.delegate = delegate;
    }

    @RuntimeType
    public Object intercept(@Origin Method method, @AllArguments Object[] args, @This Object proxy) throws Exception {
        System.out.println("Interceptando método: " + method.getName());
        Object result = method.invoke(delegate, args);
        System.out.println("Finalizado: " + method.getName());
        return result;
    }

}
