package dtm.di.annotations.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca o parâmetro de um método {@link EventListener} que receberá o evento publicado.
 *
 * <p>Necessário apenas quando o listener possui mais de um parâmetro. Nesse caso, o parâmetro
 * anotado com {@code @Event} recebe o evento e os demais parâmetros são resolvidos via
 * injeção de dependência (mesma semântica de injeção em construtores e métodos do container).</p>
 *
 * <p>Para listeners com um único parâmetro, a anotação é opcional — esse parâmetro é
 * automaticamente considerado o evento.</p>
 *
 * <pre>{@code
 * @EventListener
 * public void onOrderPlaced(@Event OrderPlacedEvent event, EmailService email, AuditLogger audit) {
 *     // 'event' vem do publisher; 'email' e 'audit' vêm do container
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Event {
}
