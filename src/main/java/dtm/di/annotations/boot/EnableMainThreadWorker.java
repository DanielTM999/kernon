package dtm.di.annotations.boot;

import dtm.di.annotations.Import;
import dtm.di.aop.mainthread.RunOnMainThreadAspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Import({RunOnMainThreadAspect.class})
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EnableMainThreadWorker {
    boolean staticCaller() default false;
}
