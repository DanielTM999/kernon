package dtm.di.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Indica que um método deve ser executado logo após a criação e injeção
 * de dependências de um componente pelo contêiner de injeção.
 *
 * Pode ser usado para inicializações adicionais, validações ou qualquer
 * lógica que precise ocorrer depois que o objeto já está completamente
 * construído e pronto para uso.
 *
 * A anotação suporta a definição de uma ordem de execução através do atributo {@link #order()},
 * permitindo controlar a sequência de execução quando múltiplos métodos
 * anotados com {@code @PostCreation} existem em um mesmo componente.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PostCreation {
    int order() default 0;
}
