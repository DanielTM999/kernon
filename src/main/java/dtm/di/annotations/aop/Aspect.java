package dtm.di.annotations.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca uma classe como um Aspecto para programação orientada a aspectos (AOP).
 *
 * Classes anotadas com @Aspect contêm definições de advices e pointcuts,
 * permitindo interceptar e adicionar comportamento antes, depois ou ao redor
 * da execução de métodos em outras classes.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Aspect { }
