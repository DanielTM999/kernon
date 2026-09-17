package dtm.di.annotations.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Habilita o registro de metodos {@link EventListener} de uma classe, ou marca o parametro
 * de um metodo {@link EventListener} que recebera o evento publicado.
 *
 * <p>Em classe, a anotacao tem dois usos:</p>
 *
 * <p><strong>1. Beans do container principal.</strong> No scan inicial do boot, so entram no
 * {@code EventPublisher} os beans cuja classe esta marcada com {@code @Event}. Um
 * {@code @Component} com metodos {@link EventListener} mas sem {@code @Event} e ignorado
 * pelo scan. Beans prototype seguem
 * {@code dependencyContainer.prototypeListenerPolicy}, cujo padrao nao os registra por nao
 * existir instancia unica.</p>
 *
 * <p><strong>2. Instancias fora do container.</strong> Permite que instancias criadas por
 * {@code DependencyContainer#newInstance(...)} tenham seus metodos {@link EventListener}
 * registrados no {@code EventPublisher}, sem registrar a instancia como dependencia do
 * container.</p>
 *
 * <p>A carga externa ({@code loadExternal}) nao segue essa regra: la o registro acontece pela
 * simples presenca de metodos {@link EventListener} na classe, sem exigir {@code @Event}.</p>
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
