package dtm.di.event.impl;

import dtm.di.annotations.Inject;
import dtm.di.annotations.Qualifier;
import dtm.di.annotations.aop.DisableAop;
import dtm.di.annotations.event.Event;
import dtm.di.annotations.event.EventListener;
import dtm.di.common.reflection.ReflectionCache;
import dtm.di.core.DependencyContainer;
import dtm.di.event.EventListenerPublisher;
import dtm.di.event.EventListenerRegistration;
import dtm.di.event.EventPublisher;
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
import java.util.function.Consumer;

/**
 * Implementação padrão do publicador de eventos.
 *
 * <p>Suporta listeners declarativos por métodos anotados com {@link EventListener}
 * e listeners programáticos registrados por {@link EventListenerPublisher}.</p>
 *
 * <p>Listeners declarativos são descobertos durante o {@link #scan()}.
 * Listeners programáticos são adicionados diretamente em runtime por
 * {@link #listen(Class, Consumer)} ou {@link #listenAsync(Class, Consumer)}.</p>
 *
 * <p>A seleção de listeners usa {@link Class#isAssignableFrom(Class)}, permitindo
 * que listeners de superclasses e interfaces recebam eventos compatíveis.</p>
 */
@Slf4j
@DisableAop
public class DefaultEventPublisher implements EventPublisher, EventListenerPublisher {

    private final DependencyContainer container;
    private final Executor asyncExecutor;
    private final List<Binding> bindings = new ArrayList<>();
    private volatile boolean scanned = false;

    /**
     * Cria o publicador padrão de eventos.
     *
     * @param container container usado para resolver dependências de listeners declarativos
     * @param asyncExecutor executor usado para listeners assíncronos
     */
    public DefaultEventPublisher(DependencyContainer container, Executor asyncExecutor) {
        this.container = container;
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * Publica um evento para todos os listeners compatíveis.
     *
     * @param event evento publicado
     */
    @Override
    public void publish(Object event) {
        if (event == null) {
            return;
        }

        if (!scanned) {
            scan();
        }

        Class<?> eventClass = event.getClass();
        List<Binding> snapshot;

        synchronized (bindings) {
            snapshot = new ArrayList<>(bindings);
        }

        for (Binding binding : snapshot) {
            if (!binding.eventType().isAssignableFrom(eventClass)) {
                continue;
            }

            if (binding.async() && asyncExecutor != null) {
                CompletableFuture.runAsync(() -> invoke(binding, event), asyncExecutor)
                        .exceptionally(ex -> {
                            log.error(
                                    "Erro em listener async. listener={}, eventType={}, message={}",
                                    binding.name(),
                                    binding.eventType().getName(),
                                    ex.getMessage(),
                                    ex
                            );
                            return null;
                        });
                continue;
            }

            invoke(binding, event);
        }
    }

    /**
     * Registra um listener programático síncrono.
     *
     * @param targetClass tipo de evento escutado
     * @param consumer listener que receberá o evento
     * @param <T> tipo do evento
     * @return registro removível do listener
     */
    @Override
    public <T> EventListenerRegistration listen(Class<T> targetClass, Consumer<T> consumer) {
        return addConsumerBinding(targetClass, consumer, false, 0);
    }

    /**
     * Registra um listener programático assíncrono.
     *
     * @param targetClass tipo de evento escutado
     * @param consumer listener que receberá o evento
     * @param <T> tipo do evento
     * @return registro removível do listener
     */
    @Override
    public <T> EventListenerRegistration listenAsync(Class<T> targetClass, Consumer<T> consumer) {
        return addConsumerBinding(targetClass, consumer, true, 0);
    }

    public EventListenerRegistration registerListeners(Object listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener nao pode ser null");
        }

        return registerListeners(listener, listener.getClass());
    }

    public EventListenerRegistration registerListeners(Object listener, Class<?> listenerClass) {
        if (listener == null) {
            throw new IllegalArgumentException("listener nao pode ser null");
        }

        if (listenerClass == null) {
            throw new IllegalArgumentException("listenerClass nao pode ser null");
        }

        List<Binding> collected = collectMethodBindings(listener, listenerClass);

        if (collected.isEmpty()) {
            return () -> {};
        }

        synchronized (bindings) {
            bindings.addAll(collected);
            bindings.sort(Comparator.comparingInt(Binding::order));
        }

        log.debug(
                "Listeners de instancia registrados. listenerClass={}, count={}",
                listenerClass.getName(),
                collected.size()
        );

        return () -> unregisterMethodBindings(collected, listenerClass);
    }

    /**
     * Indexa os listeners declarativos encontrados nos beans do container.
     *
     * <p>O método é idempotente e pode ser chamado mais de uma vez sem duplicar
     * listeners declarativos.</p>
     */
    public synchronized void scan() {
        if (scanned) {
            return;
        }

        Map<Class<Object>, Object> all = container.getInstancesByClass(Object.class);

        if (all == null || all.isEmpty()) {
            scanned = true;
            return;
        }

        List<Binding> collected = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Object bean : all.values()) {
            if (bean == null || !visited.add(bean)) {
                continue;
            }

            collected.addAll(collectMethodBindings(bean, bean.getClass()));
        }

        synchronized (bindings) {
            bindings.addAll(collected);
            bindings.sort(Comparator.comparingInt(Binding::order));
        }

        scanned = true;

        log.debug("EventPublisher inicializado com {} listener(s)", getBindingCount());
    }

    private int getBindingCount() {
        synchronized (bindings) {
            return bindings.size();
        }
    }

    private List<Binding> collectMethodBindings(Object bean, Class<?> beanClass) {
        List<Binding> collected = new ArrayList<>();

        for (Method method : ReflectionCache.methodsWithAnnotation(beanClass, EventListener.class)) {
            Binding binding = buildMethodBinding(bean, beanClass, method);

            if (binding != null) {
                collected.add(binding);
            }
        }

        return collected;
    }

    private Binding buildMethodBinding(Object bean, Method method) {
        return buildMethodBinding(bean, bean.getClass(), method);
    }

    private Binding buildMethodBinding(Object bean, Class<?> beanClass, Method method) {
        Parameter[] params = method.getParameters();

        if (params.length == 0) {
            log.warn(
                    "@EventListener ignorado: {}#{} precisa de pelo menos 1 parâmetro",
                    beanClass.getName(),
                    method.getName()
            );
            return null;
        }

        int eventIndex = resolveEventParamIndex(params, beanClass, method);

        if (eventIndex < 0) {
            return null;
        }

        EventListener annotation = method.getAnnotation(EventListener.class);
        Class<?> eventType = params[eventIndex].getType();

        ParamResolver[] resolvers = new ParamResolver[params.length];

        for (int index = 0; index < params.length; index++) {
            if (index == eventIndex) {
                resolvers[index] = ParamResolver.event();
            } else {
                resolvers[index] = ParamResolver.dependency(params[index]);
            }
        }

        if (!method.canAccess(bean)) {
            method.setAccessible(true);
        }

        return Binding.method(
                bean,
                method,
                eventType,
                resolvers,
                annotation.async(),
                annotation.order()
        );
    }

    private int resolveEventParamIndex(Parameter[] params, Class<?> beanClass, Method method) {
        if (params.length == 1) {
            return 0;
        }

        int found = -1;

        for (int index = 0; index < params.length; index++) {
            if (params[index].isAnnotationPresent(Event.class)) {
                if (found != -1) {
                    log.warn(
                            "@EventListener ignorado: {}#{} possui mais de um parâmetro com @Event",
                            beanClass.getName(),
                            method.getName()
                    );
                    return -1;
                }

                found = index;
            }
        }

        if (found == -1) {
            log.warn(
                    "@EventListener ignorado: {}#{} possui múltiplos parâmetros e nenhum marcado com @Event",
                    beanClass.getName(),
                    method.getName()
            );
        }

        return found;
    }

    private <T> EventListenerRegistration addConsumerBinding(
            Class<T> targetClass,
            Consumer<T> consumer,
            boolean async,
            int order
    ) {
        if (targetClass == null) {
            throw new IllegalArgumentException("targetClass não pode ser null");
        }

        if (consumer == null) {
            throw new IllegalArgumentException("consumer não pode ser null");
        }

        Binding binding = Binding.consumer(targetClass, consumer, async, order);

        synchronized (bindings) {
            bindings.add(binding);
            bindings.sort(Comparator.comparingInt(Binding::order));
        }

        log.debug(
                "Listener programático registrado. targetClass={}, async={}, order={}",
                targetClass.getName(),
                async,
                order
        );

        return () -> unregisterConsumerBinding(binding, targetClass, async, order);
    }

    private <T> void unregisterConsumerBinding(
            Binding binding,
            Class<T> targetClass,
            boolean async,
            int order
    ) {
        synchronized (bindings) {
            bindings.remove(binding);
        }

        log.debug(
                "Listener programático removido. targetClass={}, async={}, order={}",
                targetClass.getName(),
                async,
                order
        );
    }

    private void unregisterMethodBindings(List<Binding> registeredBindings, Class<?> listenerClass) {
        synchronized (bindings) {
            bindings.removeAll(registeredBindings);
        }

        log.debug(
                "Listeners de instancia removidos. listenerClass={}, count={}",
                listenerClass.getName(),
                registeredBindings.size()
        );
    }

    private void invoke(Binding binding, Object event) {
        try {
            binding.invoke(container, event);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;

            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            throw new RuntimeException(cause);
        }
    }

    /**
     * Binding interno de listener.
     */
    private interface Binding {

        Class<?> eventType();

        boolean async();

        int order();

        String name();

        void invoke(DependencyContainer container, Object event) throws Exception;

        static Binding method(
                Object target,
                Method method,
                Class<?> eventType,
                ParamResolver[] resolvers,
                boolean async,
                int order
        ) {
            return new MethodBinding(
                    target,
                    method,
                    eventType,
                    resolvers,
                    async,
                    order
            );
        }

        static <T> Binding consumer(
                Class<T> eventType,
                Consumer<T> consumer,
                boolean async,
                int order
        ) {
            return new ConsumerBinding<>(
                    eventType,
                    consumer,
                    async,
                    order
            );
        }
    }

    /**
     * Binding de listener declarativo baseado em método anotado.
     */
    private static final class MethodBinding implements Binding {

        private final Object target;
        private final Method method;
        private final Class<?> eventType;
        private final ParamResolver[] resolvers;
        private final boolean async;
        private final int order;

        private MethodBinding(
                Object target,
                Method method,
                Class<?> eventType,
                ParamResolver[] resolvers,
                boolean async,
                int order
        ) {
            this.target = target;
            this.method = method;
            this.eventType = eventType;
            this.resolvers = resolvers;
            this.async = async;
            this.order = order;
        }

        @Override
        public Class<?> eventType() {
            return eventType;
        }

        @Override
        public boolean async() {
            return async;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public String name() {
            return target.getClass().getName() + "#" + method.getName();
        }

        @Override
        public void invoke(DependencyContainer container, Object event) throws Exception {
            Object[] args = new Object[resolvers.length];

            for (int index = 0; index < args.length; index++) {
                args[index] = resolvers[index].resolve(container, event);
            }

            method.invoke(target, args);
        }
    }

    /**
     * Binding de listener programático baseado em {@link Consumer}.
     *
     * @param <T> tipo do evento escutado
     */
    private static final class ConsumerBinding<T> implements Binding {

        private final Class<T> eventType;
        private final Consumer<T> consumer;
        private final boolean async;
        private final int order;

        private ConsumerBinding(
                Class<T> eventType,
                Consumer<T> consumer,
                boolean async,
                int order
        ) {
            this.eventType = eventType;
            this.consumer = consumer;
            this.async = async;
            this.order = order;
        }

        @Override
        public Class<?> eventType() {
            return eventType;
        }

        @Override
        public boolean async() {
            return async;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public String name() {
            return "Consumer<" + eventType.getName() + ">";
        }

        @Override
        public void invoke(DependencyContainer container, Object event) {
            consumer.accept(eventType.cast(event));
        }
    }

    /**
     * Resolvedor de argumento de listener declarativo.
     */
    private interface ParamResolver {

        Object resolve(DependencyContainer container, Object event);

        static ParamResolver event() {
            return (container, event) -> event;
        }

        static ParamResolver dependency(Parameter parameter) {
            Class<?> type = parameter.getType();
            String qualifier = extractQualifier(parameter);

            if (qualifier == null) {
                return (container, event) -> container.getDependency(type);
            }

            return (container, event) -> container.getDependency(type, qualifier);
        }

        private static String extractQualifier(Parameter parameter) {
            if (parameter.isAnnotationPresent(Qualifier.class)) {
                String value = parameter.getAnnotation(Qualifier.class).value();
                return value == null || value.isEmpty() ? null : value;
            }

            if (parameter.isAnnotationPresent(Inject.class)) {
                String value = parameter.getAnnotation(Inject.class).qualifier();
                return value == null || value.isEmpty() ? null : value;
            }

            return null;
        }
    }
}
