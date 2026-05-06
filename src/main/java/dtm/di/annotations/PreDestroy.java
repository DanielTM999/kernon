package dtm.di.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indica que um método deve ser executado antes que o container descarregue
 * o bean — durante {@code unload()} ou via shutdown hook do processo.
 *
 * <p>Útil para liberar recursos: fechar pools, conexões, encerrar threads, flush de buffers.
 * Simétrico a {@link PostCreation}.</p>
 *
 * <h3>Requisitos do método</h3>
 * <ul>
 *   <li>público</li>
 *   <li>sem parâmetros</li>
 *   <li>retorno {@code void}</li>
 *   <li>não pode ser estático (é invocado no bean instanciado)</li>
 * </ul>
 *
 * <p>O atributo {@link #order()} controla a ordem de execução quando há múltiplos métodos
 * anotados na mesma classe. Maior {@code order} executa primeiro (ordem reversa em relação a
 * {@link PostCreation}, refletindo a semântica natural de teardown).</p>
 *
 * <p>Exceções lançadas são logadas mas não impedem o destruir dos demais beans —
 * shutdown nunca deve ficar pela metade.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PreDestroy {
    int order() default 0;
}
