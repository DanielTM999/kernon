package dtm.di.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface BeenDefinition {
    ProxyType proxyType() default ProxyType.STATIC;

    public enum ProxyType{
        INSTANCE,
        STATIC
    }
}
