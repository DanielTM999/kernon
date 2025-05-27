package dtm.di.core.aop;

import java.lang.reflect.Method;

public abstract class AopUtils {
    public abstract void applyBefore(Method method, Object[] args, Object proxy);
    public abstract Object applyAfter(Method method, Object[] args, Object proxy, Object currentResult);
}
