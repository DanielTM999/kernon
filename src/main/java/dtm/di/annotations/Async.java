package dtm.di.annotations;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca componentes ou métodos para execução assíncrona.
 *
 * <p>Em métodos de negócio, requer o aspecto assíncrono habilitado. Em métodos produtores
 * de uma {@link Configuration}, registra imediatamente um {@code AsyncComponent<T>} e
 * executa a factory em background no executor principal do container.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Async { }
