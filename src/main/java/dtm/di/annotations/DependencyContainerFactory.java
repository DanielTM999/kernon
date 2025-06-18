package dtm.di.annotations;

import dtm.di.core.DependencyContainer;
import dtm.di.storage.containers.DependencyContainerStorage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface DependencyContainerFactory {
    Class<? extends DependencyContainer> value() default DependencyContainerStorage.class;
}
