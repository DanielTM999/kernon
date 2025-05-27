package dtm.di.aop;

import dtm.di.annotations.aop.AfterExecution;
import dtm.di.annotations.aop.Aspect;
import dtm.di.annotations.aop.BeforeExecution;
import dtm.di.annotations.aop.Pointcut;
import dtm.di.core.DependencyContainer;
import dtm.di.core.aop.AopUtils;
import dtm.di.exceptions.AspectNewInstanceException;

import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;

public class AopProxyUtils extends AopUtils {
    private static AopProxyUtils aopProxyUtils;

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
            boolean apply = true;
            if (handler.pointcut != null) {
                try {
                    handler.pointcut.setAccessible(true);
                    Object result = handler.pointcut.invoke(handler.instance, method, args, proxy);
                    if (result instanceof Boolean resultPointCut) {
                        apply = resultPointCut;
                    }
                } catch (Exception e) {
                    continue;
                }
            }

            if (!apply) continue;

            Method before = handler.before;
            if(before != null){
                try {
                    before.setAccessible(true);
                    before.invoke(handler.instance, method, args, proxy);
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
            boolean apply = true;

            if (handler.pointcut != null) {
                try {
                    handler.pointcut.setAccessible(true);
                    Object pointcutResult = handler.pointcut.invoke(handler.instance, method, args, proxy);
                    if (pointcutResult instanceof Boolean b && !b) continue;
                } catch (Exception e) {
                    continue;
                }
            }

            Method after = handler.after;
            if (after != null) {
                try {
                    after.setAccessible(true);
                    Object newResult = after.invoke(handler.instance, method, args, proxy, result);

                    if (newResult != null && method.getReturnType().isAssignableFrom(newResult.getClass())) {
                        result = newResult;
                    }
                } catch (Exception e) {
                    String className = (handler.instance != null) ? handler.instance.getClass().toString() : method.getName();
                    throw new RuntimeException("Erro no método @BeforeExecution: "+className, e);
                }
            }
        }

        return result;
    }

    public static AopUtils getInstance(DependencyContainer dependencyContainer){
        if(aopProxyUtils == null) aopProxyUtils = new AopProxyUtils(dependencyContainer);
        return aopProxyUtils;
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


    public record AspectHandler(Object instance, Method pointcut, Method before, Method after) {
    }

}
