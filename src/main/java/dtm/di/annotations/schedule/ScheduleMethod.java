package dtm.di.annotations.schedule;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Anotação para métodos que devem ser executados de forma agendada.
 *
 * <p>Permite configurar o tempo entre execuções, atraso inicial e se a execução é periódica.</p>
 *
 * <h3>Parâmetros:</h3>
 * <ul>
 *     <li><b>timeUnit</b> - Unidade de tempo usada para os valores {@code time} e {@code startDelay}.
 *     O padrão é {@link TimeUnit#MILLISECONDS}.</li>
 *     <li><b>time</b> - Intervalo de tempo entre execuções da tarefa agendada.
 *     Usado apenas se {@code periodic} for {@code true}.</li>
 *     <li><b>startDelay</b> - Atraso inicial antes da primeira execução da tarefa.</li>
 *     <li><b>periodic</b> - Indica se a tarefa deve ser executada periodicamente.
 *     Se {@code false}, o método será executado apenas uma vez após o atraso inicial.</li>
 * </ul>
 *
 * <h3>Exemplo de uso:</h3>
 * <pre>{@code
 * @ScheduleMethod(timeUnit = TimeUnit.SECONDS, time = 10, startDelay = 5, periodic = true)
 * public void tarefaPeriodica() {
 *     // lógica da tarefa executada a cada 10 segundos,
 *     // começando após 5 segundos de atraso
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ScheduleMethod {
    TimeUnit timeUnit() default TimeUnit.MILLISECONDS;
    long time() default 0;
    long startDelay() default 0;
    boolean periodic() default true;
}
