package dtm.di.storage;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
final class ServiceBeen implements Comparable<ServiceBeen>{
    private Class<?> clazz;
    private long dependencyOrder;

    @Override
    public int compareTo(ServiceBeen o) {
        return Long.compare(this.dependencyOrder, o.dependencyOrder);
    }
}
