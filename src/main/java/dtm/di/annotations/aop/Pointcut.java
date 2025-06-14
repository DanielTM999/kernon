package dtm.di.annotations.aop;

import java.lang.annotation.*;

/**
 * Define um método como um Pointcut no contexto de AOP (Programação Orientada a Aspectos).
 *
 * <p>O método anotado com {@code @Pointcut} é responsável por determinar, de forma dinâmica,
 * se um determinado método deve ser interceptado pelo aspecto.</p>
 *
 * <p>Esse método deve obrigatoriamente retornar um {@code boolean}, onde:</p>
 * <ul>
 *   <li>{@code true} - Indica que o método alvo deve ser interceptado.</li>
 *   <li>{@code false} - Indica que o método alvo não será interceptado.</li>
 * </ul>
 *
 * <p>O método pode declarar parâmetros especiais para receber contexto da execução:</p>
 * <ul>
 *   <li>{@link java.lang.reflect.Method} - Representa o método alvo que está sendo avaliado.</li>
 *   <li>{@code Object[]} - Representa os argumentos do método sendo executado.</li>
 *   <li>{@code Object proxy} - Referência ao proxy, se existir. Pode ser anotado com {@code @ProxyInstance}.</li>
 * </ul>
 *
 * <p>Exemplo de uso:</p>
 *
 * <pre>{@code
 * @Aspect
 * public class LoggingAspect {
 *
 *     @Pointcut
 *     public boolean matchServiceMethods(Method method) {
 *         return method.getDeclaringClass().getSimpleName().endsWith("Service");
 *     }
 *
 *     @BeforeExecution
 *     public void logBefore(Method method) {
 *         System.out.println("Chamando: " + method.getName());
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Pointcut {

}
