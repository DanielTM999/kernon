package dtm.di.event;

import dtm.di.annotations.Inject;
import dtm.di.annotations.Qualifier;
import dtm.di.annotations.aop.DisableAop;
import dtm.di.annotations.event.Event;
import dtm.di.annotations.event.EventListener;
import dtm.di.common.reflection.ReflectionCache;
import dtm.di.core.DependencyContainer;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
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
 * <p>Listeners com 1 parâmetro recebem o evento diretamente nele. Listeners com
 * múltiplos parâmetros precisam marcar o parâmetro receptor com {@link Event};
 * os demais são resolvidos via container, suportando {@link Qualifier} e
 * {@link Inject#qualifier()}.</p>
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
                Binding binding = buildBinding(bean, method);
                if(binding != null) collected.add(binding);
            }
        }

        collected.sort(Comparator.comparingInt(b -> b.order));
        bindings.addAll(collected);
        scanned = true;
        log.debug("EventPublisher inicializado com {} listener(s)", bindings.size());
    }

    private Binding buildBinding(Object bean, Method method){
        Class<?> beanClass = bean.getClass();
        Parameter[] params = method.getParameters();
        if(params.length == 0){
            log.warn("@EventListener ignorado: {}#{} precisa de pelo menos 1 parâmetro",
                    beanClass.getName(), method.getName());
            return null;
        }

        int eventIndex = resolveEventParamIndex(params, beanClass, method);
        if(eventIndex < 0) return null;

        EventListener annotation = method.getAnnotation(EventListener.class);
        Class<?> eventType = params[eventIndex].getType();

        ParamResolver[] resolvers = new ParamResolver[params.length];
        for(int i = 0; i < params.length; i++){
            if(i == eventIndex){
                resolvers[i] = ParamResolver.event();
            }else{
                resolvers[i] = ParamResolver.dependency(params[i]);
            }
        }

        if(!method.canAccess(bean)) method.setAccessible(true);

        return new Binding(bean, method, eventType, eventIndex, resolvers,
                annotation.async(), annotation.order());
    }

    private int resolveEventParamIndex(Parameter[] params, Class<?> beanClass, Method method){
        if(params.length == 1){
            return 0;
        }
        int found = -1;
        for(int i = 0; i < params.length; i++){
            if(params[i].isAnnotationPresent(Event.class)){
                if(found != -1){
                    log.warn("@EventListener ignorado: {}#{} possui mais de um parâmetro com @Event",
                            beanClass.getName(), method.getName());
                    return -1;
                }
                found = i;
            }
        }
        if(found == -1){
            log.warn("@EventListener ignorado: {}#{} possui múltiplos parâmetros e nenhum marcado com @Event",
                    beanClass.getName(), method.getName());
        }
        return found;
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
            Object[] args = new Object[binding.resolvers.length];
            for(int i = 0; i < args.length; i++){
                args[i] = binding.resolvers[i].resolve(container, event);
            }
            binding.method.invoke(binding.target, args);
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
        final int eventParamIndex;
        final ParamResolver[] resolvers;
        final boolean async;
        final int order;

        Binding(Object target, Method method, Class<?> eventType, int eventParamIndex,
                ParamResolver[] resolvers, boolean async, int order){
            this.target = target;
            this.method = method;
            this.eventType = eventType;
            this.eventParamIndex = eventParamIndex;
            this.resolvers = resolvers;
            this.async = async;
            this.order = order;
        }
    }

    /**
     * Estratégia de resolução de cada parâmetro do listener: ou recebe o evento publicado,
     * ou é resolvido via container (com qualifier opcional vindo de {@link Qualifier} ou
     * {@link Inject#qualifier()}).
     */
    private interface ParamResolver {
        Object resolve(DependencyContainer container, Object event);

        static ParamResolver event(){
            return (c, e) -> e;
        }

        static ParamResolver dependency(Parameter parameter){
            Class<?> type = parameter.getType();
            String qualifier = extractQualifier(parameter);
            if(qualifier == null){
                return (c, e) -> c.getDependency(type);
            }
            return (c, e) -> c.getDependency(type, qualifier);
        }

        private static String extractQualifier(Parameter parameter){
            if(parameter.isAnnotationPresent(Qualifier.class)){
                String value = parameter.getAnnotation(Qualifier.class).value();
                return (value == null || value.isEmpty()) ? null : value;
            }
            if(parameter.isAnnotationPresent(Inject.class)){
                String value = parameter.getAnnotation(Inject.class).qualifier();
                return (value == null || value.isEmpty()) ? null : value;
            }
            return null;
        }
    }
}
