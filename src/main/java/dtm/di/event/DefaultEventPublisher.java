package dtm.di.event;

import dtm.di.annotations.aop.DisableAop;
import dtm.di.annotations.event.EventListener;
import dtm.di.common.reflection.ReflectionCache;
import dtm.di.core.DependencyContainer;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Implementação padrão do {@link EventPublisher}.
 *
 * <p>No boot, {@link #scan()} percorre os beans registrados, encontra métodos
 * anotados com {@link EventListener} e indexa por tipo de evento aceito.
 * Para cada {@code publish(event)}, percorre os bindings registrados e dispara
 * os que aceitem o tipo do evento (compatibilidade via {@code isAssignableFrom},
 * suportando herança e interfaces).</p>
 *
 * <p>Despacho síncrono por padrão. Listeners marcados {@code async=true} são
 * disparados via {@link Executor} fornecido (geralmente o {@code mainExecutor}
 * do container).</p>
 */
@DisableAop
@Slf4j
public class DefaultEventPublisher implements EventPublisher {

    private final DependencyContainer container;
    private final Executor asyncExecutor;
    private final List<Binding> bindings = new ArrayList<>();
    private volatile boolean scanned = false;

    public DefaultEventPublisher(DependencyContainer container, Executor asyncExecutor){
        this.container = container;
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * Indexa os listeners varrendo todos os beans do container. Idempotente.
     * Chamado pelo container após {@code load()}.
     */
    public synchronized void scan(){
        if(scanned) return;
        Map<Class<Object>, Object> all = container.getInstancesByClass(Object.class);
        if(all == null || all.isEmpty()){
            scanned = true;
            return;
        }

        List<Binding> collected = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for(Object bean : all.values()){
            if(bean == null || !visited.add(bean)) continue;
            Class<?> beanClass = bean.getClass();
            for(Method method : ReflectionCache.methodsWithAnnotation(beanClass, EventListener.class)){
                if(method.getParameterCount() != 1){
                    log.warn("@EventListener ignorado: {}#{} deve ter exatamente 1 parâmetro",
                            beanClass.getName(), method.getName());
                    continue;
                }
                EventListener annotation = method.getAnnotation(EventListener.class);
                Class<?> eventType = method.getParameterTypes()[0];
                if(!method.canAccess(bean)){
                    method.setAccessible(true);
                }
                collected.add(new Binding(bean, method, eventType, annotation.async(), annotation.order()));
            }
        }

        collected.sort(Comparator.comparingInt(b -> b.order));
        bindings.addAll(collected);
        scanned = true;
        log.debug("EventPublisher inicializado com {} listener(s)", bindings.size());
    }

    @Override
    public void publish(Object event){
        if(event == null) return;
        if(!scanned) scan();

        Class<?> eventClass = event.getClass();
        for(Binding binding : bindings){
            if(binding.eventType.isAssignableFrom(eventClass)){
                if(binding.async && asyncExecutor != null){
                    CompletableFuture.runAsync(() -> invoke(binding, event), asyncExecutor)
                            .exceptionally(ex -> {
                                log.error("Erro em listener async {}#{}: {}",
                                        binding.target.getClass().getName(),
                                        binding.method.getName(),
                                        ex.getMessage(), ex);
                                return null;
                            });
                }else{
                    invoke(binding, event);
                }
            }
        }
    }

    private void invoke(Binding binding, Object event){
        try{
            binding.method.invoke(binding.target, event);
        }catch (Exception e){
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            if(cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    private static final class Binding {
        final Object target;
        final Method method;
        final Class<?> eventType;
        final boolean async;
        final int order;

        Binding(Object target, Method method, Class<?> eventType, boolean async, int order){
            this.target = target;
            this.method = method;
            this.eventType = eventType;
            this.async = async;
            this.order = order;
        }
    }
}
