package dtm.di.storage;

import lombok.Data;

import java.lang.reflect.Type;

@Data
public final class ServiceBean implements Comparable<ServiceBean>{
    private Class<?> clazz;
    private long dependencyOrder;
    private boolean aop;
    private Type declaredGenericType;

    public ServiceBean(Class<?> clazz, long dependencyOrder, boolean aop) {
        this(clazz, dependencyOrder, aop, null);
    }

    public ServiceBean(Class<?> clazz, long dependencyOrder, boolean aop, Type declaredGenericType) {
        this.clazz = clazz;
        this.dependencyOrder = dependencyOrder;
        this.aop = aop;
        this.declaredGenericType = declaredGenericType;
    }

    @Override
    public int compareTo(ServiceBean o) {
        return Long.compare(this.dependencyOrder, o.dependencyOrder);
    }
}
