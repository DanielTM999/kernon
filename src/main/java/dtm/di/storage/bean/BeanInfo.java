package dtm.di.storage.bean;

import lombok.Builder;
import lombok.Data;

import java.lang.reflect.Method;
import java.util.Set;

@Data
@Builder
public class BeanInfo {
    private String beanId;
    private Class<?> producedType;
    private Class<?> configClass;
    private Method method;
    private Set<String> dependencies;
    private boolean singleton;
    private boolean aop;
    private int layer;
    private boolean dependsOnUserServices;

    public boolean isBeanMethod() {
        return method != null;
    }
}
