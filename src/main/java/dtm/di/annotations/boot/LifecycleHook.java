package dtm.di.annotations.boot;
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LifecycleHook {

    Event value() default Event.AFTER_CONTAINER_LOAD;
    int order() default 0;

    public enum Event {
        BEFORE_ALL,
        AFTER_CONTAINER_LOAD,
        AFTER_STARTUP_METHOD,
        AFTER_ALL
    }
}
