package dtm.di.aop;

import dtm.di.annotations.aop.*;
import dtm.di.core.DependencyContainer;
import dtm.di.core.aop.AopUtils;
import dtm.di.exceptions.AspectNewInstanceException;

import java.lang.reflect.Parameter;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;

public class AopProxyUtils extends AopUtils {
    private static volatile AopProxyUtils aopProxyUtils;

    private final DependencyContainer dependencyContainer;
    private final Set<AspectHandler> handlers;

    private AopProxyUtils(DependencyContainer dependencyContainer){
        this.dependencyContainer = dependencyContainer;
        this.handlers = ConcurrentHashMap.newKeySet();
        createAspects();
    }

    @Override
    public void applyBefore(Method method, Object[] args, Object proxy) {
        for (AspectHandler handler : handlers) {
            boolean apply = shouldApplyHandler(handler, method, args, proxy);

            if(!apply) continue;

            Method before = handler.before;
            if(before != null){
                try {
                    executeMethod(
                            handler.instance,
                            before,
                            method,
                            args,
                            proxy
                    );
                } catch (Exception e) {
                    String className = (handler.instance != null) ?  handler.instance.getClass().toString() : method.getName();
                    throw new RuntimeException("Erro no método @BeforeExecution: "+className, e);
                }
            }
        }

    }

    @Override
    public Object applyAfter(Method method, Object[] args, Object proxy, Object currentResult) {
        Object result = currentResult;

        for (AspectHandler handler : handlers) {
            boolean apply = shouldApplyHandler(handler, method, args, proxy);

            if(!apply) continue;

            Method after = handler.after;
            if (after != null) {
                try {
                    after.setAccessible(true);
                    Object newResult = executeMethod(
                            handler.instance,
                            after,
                            method,
                            args,
                            proxy,
                            result
                    );
                    if (newResult != null && method.getReturnType().isAssignableFrom(newResult.getClass())) {
                        result = newResult;
                    }
                } catch (Exception e) {
                    String className = (handler.instance != null) ? handler.instance.getClass().toString() : method.getName();
                    throw new RuntimeException("Erro no método @AfterExecution: "+className, e);
                }
            }
        }

        return result;
    }

    private void createAspects(){
        Set<Class<?>> aspects = dependencyContainer
                .getLoadedSystemClasses()
                .parallelStream()
                .filter(c -> c.isAnnotationPresent(Aspect.class))
                .collect(Collectors.toSet());

        for (Class<?> clazz : aspects){
            try{
                Object instance = dependencyContainer.newInstance(clazz);
                Method pointcut = null, before = null, after = null;
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Pointcut.class))
                        pointcut = method;
                    else if (method.isAnnotationPresent(BeforeExecution.class))
                        before = method;
                    else if (method.isAnnotationPresent(AfterExecution.class))
                        after = method;
                }

                handlers.add(new AspectHandler(instance, pointcut, before, after));
            }catch (Exception e){
                throw new AspectNewInstanceException(e.getMessage(), clazz, e);
            }
        }
    }

    private boolean shouldApplyHandler(AspectHandler handler, Method method, Object[] args, Object proxy) {
        if (handler.pointcut == null) return true;
        try {
            Object result = executeMethod(handler.instance, handler.pointcut, method, args, proxy);
            return result instanceof Boolean b && b;
        } catch (Exception e) {
            return false;
        }
    }


    public Object executeMethod(
            Object instance,
            Method methodExecute,
            Method methodArgs,
            Object[] args,
            Object proxy
    ) throws Exception{
        return executeMethod(instance, methodExecute, methodArgs, args, proxy, null);
    }

    public Object executeMethod(
            Object instance,
            Method methodExecute,
            Method methodArgs,
            Object[] args,
            Object proxy,
            Object currentResult
    ) throws Exception{
        methodExecute.setAccessible(true);
        Parameter[] parameters = methodExecute.getParameters();
        Object[] invokeArgs = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            Class<?> paramType = parameter.getType();

            boolean isProxy = parameter.isAnnotationPresent(ProxyInstance.class);
            boolean isResult = parameter.isAnnotationPresent(ResultProxy.class);

            if (isProxy) {
                invokeArgs[i] = proxy;
            } else if (isResult) {
                invokeArgs[i] = currentResult;
            } else if (Method.class.isAssignableFrom(paramType)) {
                invokeArgs[i] = methodArgs;
            } else if (paramType.isArray() && paramType.getComponentType().equals(Object.class)) {
                invokeArgs[i] = args;
            } else {
                invokeArgs[i] = null;
            }
        }

        return methodExecute.invoke(instance, invokeArgs);
    }

    public record AspectHandler(Object instance, Method pointcut, Method before, Method after) {
    }

    public static AopUtils getInstance(DependencyContainer dependencyContainer) {
        if (aopProxyUtils == null) {
            synchronized (AopProxyUtils.class) {
                if (aopProxyUtils == null) {
                    aopProxyUtils = new AopProxyUtils(dependencyContainer);
                }
            }
        }
        return aopProxyUtils;
    }

}
