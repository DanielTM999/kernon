package dtm.di.utils;

import dtm.di.annotations.aop.ProxyInstance;
import dtm.di.prototypes.ProxyObject;

public final class ProxyUtils {

    private ProxyUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static boolean isProxy(Object object) {
        return object instanceof ProxyObject && object.getClass().isAnnotationPresent(ProxyInstance.class);
    }

    public static Object getRealInstanceProxy(Object object) {
        if(isProxy(object)) {
            return ((ProxyObject)object).getRealInstance();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getRealInstanceProxyAs(Object object) {
       try{
           if(isProxy(object)) {
               return (T)((ProxyObject)object).getRealInstance();
           }
       }catch(ClassCastException ignored) {}
        return null;
    }
}
