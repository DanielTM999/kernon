package dtm.di.annotations.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca uma classe criada por {@code newInstance} como participante do sistema
 * de eventos ou marca o parametro de um metodo {@link EventListener} que
 * recebera o evento publicado.
 *
 * <p>Quando usada em uma classe, permite que instancias criadas por
 * {@code DependencyContainer#newInstance(...)} tenham seus metodos
 * {@link EventListener} registrados no {@code EventPublisher}, sem registrar a
 * instancia como dependencia do container.</p>
 *
 * <p>Quando usada em parametros, e necessaria apenas quando o listener possui
 * mais de um parametro. Nesse caso, o parametro anotado com {@code @Event}
 * recebe o evento e os demais parametros sao resolvidos via injecao de
 * dependencia.</p>
 *
 * <pre>{@code
 * @EventListener
 * public void onOrderPlaced(@Event OrderPlacedEvent event, EmailService email, AuditLogger audit) {
 *     // 'event' vem do publisher; 'email' e 'audit' vem do container
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
public @interface Event {
}
