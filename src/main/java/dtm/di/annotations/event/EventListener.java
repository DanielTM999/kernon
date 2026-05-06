package dtm.di.annotations.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca um método como handler de eventos publicados pelo {@code EventPublisher}.
 *
 * <p>O método deve receber exatamente um parâmetro — o tipo desse parâmetro define
 * qual evento será roteado para ele. Eventos publicados são entregues a todos os
 * listeners cujo parâmetro seja atribuível a partir do tipo do evento (suporta
 * herança e interfaces).</p>
 *
 * <h3>Requisitos</h3>
 * <ul>
 *   <li>Não estático</li>
 *   <li>Exatamente um parâmetro</li>
 *   <li>Retorno {@code void} (retornos são ignorados)</li>
 *   <li>O bean dono do método deve estar registrado no container ({@code @Component}/{@code @Service})</li>
 * </ul>
 *
 * <h3>Modos de despacho</h3>
 * <p>Por padrão {@code async = false}: handlers rodam síncrono na thread chamadora.
 * Com {@code async = true}, o handler roda no executor compartilhado do container.
 * Exceções de handlers async são logadas; síncronas propagam ao publisher.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EventListener {
    boolean async() default false;
    int order() default 0;
}
