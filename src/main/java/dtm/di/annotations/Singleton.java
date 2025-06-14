package dtm.di.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indica que uma classe deve ser tratada como singleton pelo contêiner de dependências.
 *
 * Ao marcar uma classe com esta anotação, o contêiner garante que apenas uma única
 * instância da classe será criada e reutilizada durante todo o ciclo de vida da aplicação.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Singleton {
}
