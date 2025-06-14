package dtm.di.annotations.boot;
import java.lang.annotation.*;

/**
 * Anotação para marcar métodos que serão executados em determinados eventos
 * do ciclo de vida da aplicação dentro do contêiner de injeção de dependências.
 *
 * <p>Permite definir o momento da execução do método e a ordem de execução
 * relativa a outros hooks do mesmo evento.</p>
 *
 * <h3>Parâmetros:</h3>
 * <ul>
 *   <li><b>value</b> - Evento do ciclo de vida no qual o método será executado.
 *       Valores possíveis definidos na enum {@link Event}:
 *       <ul>
 *          <li>BEFORE_ALL: antes de qualquer inicialização.</li>
 *          <li>AFTER_CONTAINER_LOAD: após o carregamento do contêiner de dependências.</li>
 *          <li>AFTER_STARTUP_METHOD: após a execução do método de startup.</li>
 *          <li>AFTER_ALL: após toda a inicialização estar concluída.</li>
 *       </ul>
 *   </li>
 *   <li><b>order</b> - Ordem relativa de execução para hooks do mesmo evento.
 *       Menores valores são executados primeiro. Padrão é 0.</li>
 * </ul>
 *
 * <h3>Exemplo de uso:</h3>
 * <pre>{@code
 * public class MyAppHooks {
 *
 *     @LifecycleHook(value = LifecycleHook.Event.BEFORE_ALL, order = 1)
 *     public static void prepare() {
 *         // código executado antes de tudo
 *     }
 *
 *     @LifecycleHook(value = LifecycleHook.Event.AFTER_CONTAINER_LOAD)
 *     public static void initServices() {
 *         // código executado após carregar contêiner
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LifecycleHook {

    Event value() default Event.AFTER_CONTAINER_LOAD;
    int order() default 0;

    public enum Event {
        BEFORE_ALL,
        AFTER_CONTAINER_LOAD,
        AFTER_STARTUP_METHOD,
        AFTER_ALL
    }
}
