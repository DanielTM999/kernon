package dtm.di.event;

/**
 * Publicador de eventos do container — injetável em qualquer bean.
 *
 * <p>Uso típico:
 * <pre>{@code
 * @Service
 * public class OrderService {
 *     @Inject
 *     private EventPublisher events;
 *
 *     public void place(Order order) {
 *         // ...
 *         events.publish(new OrderPlacedEvent(order));
 *     }
 * }
 * }</pre>
 *
 * <p>Listeners são métodos anotados com
 * {@link dtm.di.annotations.event.EventListener} em beans do container,
 * descobertos no boot. Despacho é síncrono por padrão; o flag {@code async}
 * no listener move a invocação para o executor do container.</p>
 */
public interface EventPublisher {
    /**
     * Publica um evento. Todos os listeners cujo parâmetro seja atribuível a partir
     * de {@code event.getClass()} serão invocados.
     *
     * @param event o evento — não pode ser {@code null}
     */
    void publish(Object event);
}
