package dtm.di.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indica que a classe é uma classe de configuração para o contêiner de dependências.
 *
 * Classes anotadas com @Configuration geralmente definem beans e configurações de componentes,
 * podendo conter métodos que criam e configuram objetos gerenciados pelo contêiner.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Configuration { }

