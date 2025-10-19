package dtm.di.storage.composite;

import dtm.di.prototypes.CompositeDependency;
import java.util.Iterator;
import java.util.List;

public class CompositeDependencyStorage<T> implements CompositeDependency<T> {

    private final List<T> baseList;
    private final Iterator<T> iterator;

    public CompositeDependencyStorage(List<T> baseList) {
        this.baseList = baseList;
        this.iterator = baseList.iterator();
    }

    @Override
    public List<T> getAsList() {
        return List.copyOf(baseList);
    }

    @Override
    public boolean hasNext() {
        return iterator.hasNext();
    }

    @Override
    public T next() {
        return iterator.next();
    }
}
