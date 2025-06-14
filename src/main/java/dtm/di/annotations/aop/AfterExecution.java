package dtm.di.annotations.aop;

import java.lang.annotation.*;

/**
 * Indica que o método anotado deve ser executado <b>após</b> a execução
 * de um método alvo, como parte de um Aspecto (AOP).
 *
 * <p>O método anotado com {@code @AfterExecution} pode acessar o resultado da execução
 * do método interceptado e, além disso, pode modificar esse resultado, pois seu retorno
 * será, de fato, o valor retornado ao chamador original.</p>
 *
 * <h3> Comportamento:</h3>
 * <ul>
 *     <li>O valor retornado pelo método anotado será usado como retorno final
 *     da execução interceptada.</li>
 *     <li>Se o método anotado retornar {@code null}, este será o valor entregue ao chamador,
 *     desde que seja compatível com o tipo de retorno esperado.</li>
 *     <li>O método pode acessar o resultado original utilizando um parâmetro anotado com {@link dtm.di.annotations.aop.ResultProxy}.</li>
 * </ul>
 *
 * <h3> Parâmetros suportados:</h3>
 * <ul>
 *     <li>{@link java.lang.reflect.Method} → Representa o método alvo que foi executado.</li>
 *     <li>{@code Object[]} → Argumentos utilizados na execução do método interceptado.</li>
 *     <li>{@code Object proxy} → Instância do proxy, se anotado com {@link dtm.di.annotations.aop.ProxyInstance}.</li>
 *     <li>{@code Object} → O resultado original do método interceptado, se anotado com {@link dtm.di.annotations.aop.ResultProxy}.</li>
 * </ul>
 * <p>Consulte {@link dtm.di.annotations.aop.ProxyInstance} para entender como acessar o proxy da instância atual.</p>
 * <p>Consulte {@link dtm.di.annotations.aop.ResultProxy} para acessar ou modificar o valor retornado pelo método interceptado.</p>
 * <h3> Importante:</h3>
 * <ul>
 *     <li>O tipo de retorno do método anotado deve ser compatível com o tipo de retorno
 *     do método interceptado.</li>
 *     <li>Se não for compatível, uma exceção de tipo poderá ocorrer em tempo de execução.</li>
 * </ul>
 *
 * <h3> Exemplo de uso:</h3>
 * <pre>{@code
 * @Aspect
 * public class LoggerAspect {
 *
 *     @AfterExecution
 *     public Object modifyResult(Method method, @ResultProxy Object result) {
 *         System.out.println("Método " + method.getName() + " retornou: " + result);
 *         if (result instanceof String) {
 *             return ((String) result).toUpperCase();
 *         }
 *         return result;
 *     }
 * }
 * }</pre>
 *
 * <p>Neste exemplo, o valor de retorno do método interceptado é convertido para
 * maiúsculas, se for uma {@code String}.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AfterExecution {
}