package dtm.di.annotations.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indica que o parâmetro anotado deve receber o resultado da execução
 * do método interceptado.
 *
 * <p>Quando utilizado em métodos anotados com {@link AfterExecution} ,
 * esse parâmetro receberá o valor que foi retornado pelo método interceptado.</p>
 *
 * <h3> Utilização:</h3>
 * <ul>
 *     <li>Permite acessar o resultado original da execução do método interceptado.</li>
 *     <li>Combinado com {@link AfterExecution}, é possível alterar esse resultado retornando um novo valor.</li>
 * </ul>
 *
 * <h3> Observações:</h3>
 * <ul>
 *     <li>Se o método interceptado retornar {@code void}, o valor será {@code null}.</li>
 *     <li>Deve ser usado somente em advices que são executados <b>após</b> o método (After ou Pointcut).</li>
 * </ul>
 *
 * <h3> Exemplo:</h3>
 * <pre>{@code
 * @Aspect
 * public class ModifyResultAspect {
 *
 *     @AfterExecution
 *     public Object changeResult(@ResultProxy Object result) {
 *         if (result instanceof String) {
 *             return ((String) result).toUpperCase();
 *         }
 *         return result;
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ResultProxy {
}