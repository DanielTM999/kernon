package dtm.di.annotations.boot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indica que o método anotado deve ser executado durante o processo de boot
 * da aplicação, após o carregamento do contêiner de dependências e antes do
 * início completo do sistema.
 *
 * <p>Usada para definir métodos que inicializam componentes, serviços ou
 * executam tarefas de configuração essenciais na inicialização da aplicação.</p>
 *
 * <h3>Exemplo de uso:</h3>
 * <pre>{@code
 * public class AppInitializer {
 *
 *     @OnBoot
 *     public static void initialize() {
 *         // Código de inicialização
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OnBoot { }
