package dtm.di.annotations.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indica que o parâmetro anotado deve receber a instância do proxy
 * que está executando o método interceptado.
 *
 * <p>Quando utilizado em métodos anotados com {@link BeforeExecution}, {@link AfterExecution}
 * ou {@link Pointcut}, o parâmetro automaticamente recebe a instância do proxy gerado
 * pelo container de injeção de dependências.</p>
 *
 * <h3> Utilização:</h3>
 * <ul>
 *     <li>Permite que o advice (Before, After, Pointcut) acesse o próprio proxy que está executando o método.</li>
 *     <li>Útil para cenários onde é necessário acessar métodos ou dados da própria instância proxy.</li>
 * </ul>
 *
 * <h3> Observações:</h3>
 * <ul>
 *     <li>Se não houver proxy gerado, o valor injetado será {@code null}.</li>
 *     <li>O tipo do parâmetro deve ser compatível com a instância do proxy.</li>
 * </ul>
 *
 * <h3> Exemplo:</h3>
 * <pre>{@code
 * @Aspect
 * public class LoggerAspect {
 *
 *     @BeforeExecution
 *     public void logCall(@ProxyInstance Object proxy) {
 *         System.out.println("Chamando método da instância: " + proxy);
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ProxyInstance {
}