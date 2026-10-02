package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Inject;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;
import dtm.di.prototypes.CompositeDependency;
import dtm.di.prototypes.LazyDependency;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Singleton
@Component
@Profile("generics")
public class GenericWrapperConsumer {

    @Inject
    private LazyDependency<GenericProcessor<FooPayload>> lazyFoo;

    @Inject
    private CompositeDependency<GenericProcessor<FooPayload>> compositeFoo;

    @Inject
    private CompositeDependency<GenericProcessor<?>> compositeAll;

    @Inject
    private List<GenericProcessor<FooPayload>> listFoo;

    @Inject
    private AtomicReference<GenericProcessor<FooPayload>> referenceFoo;

    @Inject
    private Map<String, GenericProcessor<FooPayload>> mapFoo;

    public Map<String, GenericProcessor<FooPayload>> mapFoo() {
        return mapFoo;
    }

    public LazyDependency<GenericProcessor<FooPayload>> lazyFoo() {
        return lazyFoo;
    }

    public CompositeDependency<GenericProcessor<FooPayload>> compositeFoo() {
        return compositeFoo;
    }

    public CompositeDependency<GenericProcessor<?>> compositeAll() {
        return compositeAll;
    }

    public List<GenericProcessor<FooPayload>> listFoo() {
        return listFoo;
    }

    public AtomicReference<GenericProcessor<FooPayload>> referenceFoo() {
        return referenceFoo;
    }
}
