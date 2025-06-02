package dtm.di.annotations.schedule;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ScheduleMethod {
    TimeUnit timeUnit() default TimeUnit.MILLISECONDS;
    long time() default 0;
    long startDelay() default 0;
    boolean periodic() default true;
}
