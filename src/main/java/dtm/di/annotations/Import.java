package dtm.di.annotations;

import java.lang.annotation.*;

/**
 * Indica que classes de configuração ou componentes adicionais devem ser importados
 * e processados pelo Injetor de Dependência.
 *
 * <p>Quando o injetor encontra uma classe anotada com {@code @Import}, ele adiciona
 * as classes especificadas no atributo {@link #value()} à sua <b>fila de análise</b>.
 * Isso garante que essas classes sejam escaneadas, instanciadas e registradas no
 * contexto da aplicação, mesmo que não estejam no pacote base de varredura.</p>
 *
 * <p><b>Exemplo de uso:</b></p>
 * <pre>{@code
 * // O injetor irá ler a AppConfig e, consequentemente,
 * // colocará a DatabaseConfig e a SecurityConfig na fila de análise.
 * @Configuration
 * @Import({DatabaseConfig.class, SecurityConfig.class})
 * public class AppConfig {
 * // ...
 * }
 * }</pre>
 *
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Import {
    Class<?>[] value() default {};
}
