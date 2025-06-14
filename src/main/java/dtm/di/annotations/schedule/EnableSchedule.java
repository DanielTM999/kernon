package dtm.di.annotations.schedule;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Habilita o suporte a agendamento de tarefas na aplicação.
 *
 * <p>Essa anotação deve ser aplicada em uma classe de configuração ou na classe principal
 * para ativar o mecanismo de agendamento interno.</p>
 *
 * <p>O número de threads dedicadas ao agendamento pode ser configurado
 * através do atributo {@code threads}.</p>
 *
 * <h3>Exemplo de uso:</h3>
 * <pre>{@code
 * @EnableSchedule(threads = 4)
 * public class AppConfig {
 *     // Configurações da aplicação
 * }
 * }</pre>
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EnableSchedule {
    int threads() default 2;
}
