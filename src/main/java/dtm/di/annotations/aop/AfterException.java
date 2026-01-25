package dtm.di.annotations.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indica que o método anotado deve ser executado **quando um método interceptado lançar uma exceção**.
 *
 * <p>O método anotado com {@code @OnError} pode acessar a exceção lançada pelo método alvo,
 * os argumentos utilizados e a instância do proxy.</p>
 *
 * <h3>Parâmetros suportados:</h3>
 * <ul>
 *     <li>{@link java.lang.reflect.Method} → Representa o método alvo que lançou a exceção.</li>
 *     <li>{@code Object[]} → Argumentos utilizados na execução do método interceptado.</li>
 *     <li>{@code Object proxy} → Instância do proxy, se anotado com {@link dtm.di.annotations.aop.ProxyInstance}.</li>
 *     <li>{@code Throwable} → A exceção lançada pelo método interceptado.</li>
 * </ul>
 *
 * <h3>Exemplo de uso:</h3>
 * <pre>{@code
 * @Aspect
 * public class LoggerAspect {
 *
 *     @OnError
 *     public void logError(Method method, @ProxyInstance Object proxy, Throwable error) {
 *         System.err.println("Erro no método " + method.getName() + ": " + error.getMessage());
 *     }
 * }
 * }</pre>
 *
 * <p>Este exemplo imprime o erro ocorrido no método interceptado.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AfterException {
}
