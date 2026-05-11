package dtm.di.event;

import java.util.function.Consumer;

public interface EventListenerPublisher {
    <T> EventListenerRegistration listen(Class<T> targetClass, Consumer<T> consumer);
    <T> EventListenerRegistration listenAsync(Class<T> targetClass, Consumer<T> consumer);
}
