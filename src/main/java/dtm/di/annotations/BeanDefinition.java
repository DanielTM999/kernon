package dtm.di.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configura o escopo de um método produtor de bean.
 *
 * <p><strong>Esta anotação não descobre o método sozinha.</strong> A descoberta de produtores
 * testa a presença de {@link Component} (diretamente ou como meta-anotação, o que inclui
 * {@link Service}). Um método anotado apenas com {@code @BeanDefinition} é ignorado pelo
 * contêiner.</p>
 *
 * <p>Combine com a anotação produtora e use {@link ProxyType} para escolher o escopo:</p>
 *
 * <pre>{@code
 * @Configuration
 * public class Config {
 *
 *     @Component
 *     @BeanDefinition(proxyType = BeanDefinition.ProxyType.INSTANCE)
 *     public Report report() {
 *         return new Report();
 *     }
 * }
 * }</pre>
 *
 * <p>{@link ProxyType#STATIC}, o padrão, registra o bean como singleton.
 * {@link ProxyType#INSTANCE} registra como prototype: cada resolução cria uma instância nova.
 * Nesse caso a classe retornada precisa ter construtor vazio público, então lambdas e classes
 * anônimas não são aceitas.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface BeanDefinition {
    ProxyType proxyType() default ProxyType.STATIC;


    public enum ProxyType{
        INSTANCE,
        STATIC
    }
}
