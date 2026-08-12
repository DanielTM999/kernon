package dtm.di.annotations.boot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara explicitamente a classe de entrada usada pelo bootstrap do Kernon.
 *
 * <p>Use esta anotação quando a aplicação não expõe o método convencional
 * {@code public static void main(String[] args)}. A classe anotada deve estar
 * na pilha de chamadas que invoca {@code ManagedApplicationStartup.doRun}.</p>
 *
 * <p>Quando nenhuma classe da pilha possui esta anotação, o Kernon mantém a
 * descoberta legada pelo método {@code main(String[])}.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ApplicationEntryPoint {
}
