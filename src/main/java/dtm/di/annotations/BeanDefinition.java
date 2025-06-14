package dtm.di.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca um método como uma definição de bean para o contêiner de dependências.
 *
 * O método anotado será usado para criar e configurar uma instância gerenciada pelo contêiner.
 *
 * Permite configurar o tipo de proxy usado para o bean através do atributo {@link ProxyType}.
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
