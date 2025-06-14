package dtm.di.annotations.boot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Anotação para indicar a classe principal que inicializa a aplicação.
 *
 * <p>Deve ser colocada na classe de configuração ou no ponto de entrada da aplicação
 * para definir qual classe será usada para iniciar o framework ou o contêiner de
 * injeção de dependências.</p>
 *
 * <h3>Parâmetro:</h3>
 * <ul>
 *     <li><b>value</b> - Classe que representa o ponto principal de boot da aplicação.</li>
 * </ul>
 *
 * <h3>Exemplo de uso:</h3>
 * <pre>{@code
 * @ApplicationBoot(MyApplication.class)
 * public class AppConfig {
 *     // configurações adicionais
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ApplicationBoot {
    Class<?> value();
}
