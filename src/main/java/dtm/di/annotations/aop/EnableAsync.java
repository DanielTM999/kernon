package dtm.di.annotations.aop;

import dtm.di.annotations.Import;
import dtm.di.aop.async.AsyncAspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Import({AsyncAspect.class})
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EnableAsync {}
