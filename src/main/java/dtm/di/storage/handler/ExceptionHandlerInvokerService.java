package dtm.di.storage.handler;

import dtm.di.annotations.handler.OnException;
import dtm.di.core.DependencyContainer;
import dtm.di.core.ExceptionHandlerInvoker;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Method;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ExceptionHandlerInvokerService implements ExceptionHandlerInvoker {
    private final Object instanceHandler;
    private final Map<Class<? extends Throwable>, Method> classMethodMap;
    private final Class<?> aClass;
    private final AtomicBoolean load;
    private final Future<?> loadAction;


    public ExceptionHandlerInvokerService(Class<?> aClass, DependencyContainer dependencyContainer){
        this.aClass = aClass;
        this.instanceHandler = dependencyContainer.newInstance(aClass);
        this.classMethodMap = new ConcurrentHashMap<>();
        this.load = new AtomicBoolean(false);
        this.loadAction = loadAction();
    }

    @Override
    public void invoke(Thread thread, Throwable throwable) throws Exception {
        completeLoad();
        Method method = findMethodForThrowable(throwable);

        if (method == null) {
            throw new IllegalStateException("Nenhum handler encontrado para exceção: " + throwable.getClass().getName()+ " retornando a manipulador padrão");
        }
        Object[] args = resolveMethodArguments(method, thread, throwable);

        method.invoke(instanceHandler, args);
    }

    private void completeLoad() throws ExecutionException, InterruptedException {
        if(load.compareAndSet(false, true)){
            loadAction.get();
        }
    }

    private CompletableFuture<?> loadAction(){
        return CompletableFuture.runAsync(() -> {
            List<CompletableFuture<?>> tasks = new ArrayList<>();
            try(ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()){
                for(Method method : aClass.getDeclaredMethods()){
                    CompletableFuture<?> task = CompletableFuture.runAsync(() -> {

                        if(method.isAnnotationPresent(OnException.class)){
                            OnException onException = method.getAnnotation(OnException.class);
                            Class<? extends Throwable> aClassThrow = onException.value();
                            if(aClassThrow != null){
                                classMethodMap.put(aClassThrow, method);
                            }
                        }

                    }, executorService);
                    tasks.add(task);
                }
            }
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        });
    }

    private Method findMethodForThrowable(Throwable throwable) {
        Class<?> throwableClass = throwable.getClass();

        if (classMethodMap.containsKey(throwableClass)) {
            return classMethodMap.get(throwableClass);
        }

        return classMethodMap.entrySet().stream()
                .filter(entry -> entry.getKey().isAssignableFrom(throwableClass))
                .min(Comparator.comparingInt(entry -> getInheritanceDistance(throwableClass, entry.getKey())))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    private int getInheritanceDistance(Class<?> child, Class<?> parent) {
        int distance = 0;
        Class<?> current = child;
        while (current != null && !current.equals(parent)) {
            current = current.getSuperclass();
            distance++;
        }
        return distance;
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
