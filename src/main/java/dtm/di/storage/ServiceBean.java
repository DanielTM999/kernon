package dtm.di.storage;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
final class ServiceBean implements Comparable<ServiceBean>{
    private Class<?> clazz;
    private long dependencyOrder;

    @Override
    public int compareTo(ServiceBean o) {
        return Long.compare(this.dependencyOrder, o.dependencyOrder);
    }
}
