package dtm.di.annotations.schedule;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indica que a classe anotada representa uma tarefa agendada
 * que será executada pelo mecanismo de agendamento.
 *
 * <p>Essa anotação deve ser usada em classes que definem jobs ou tarefas
 * a serem executadas periodicamente ou conforme configuração de tempo.</p>
 *
 * <p>Normalmente, as classes anotadas com {@code @Schedule} devem
 * conter métodos que definem a lógica da tarefa a ser executada.</p>
 *
 * <h3>Exemplo de uso:</h3>
 * <pre>{@code
 * @Schedule
 * public class MinhaTarefaAgendada {
 *     public void executar() {
 *         // lógica da tarefa
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Schedule { }
