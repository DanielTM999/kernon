package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Inject;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Profile("generics-wildcard")
public class GenericWildcardConsumer {

    @Inject
    private GenericProcessor<? super Integer> lowerBounded;

    @Inject
    private GenericProcessor<FooPayload> exact;

    public GenericProcessor<? super Integer> lowerBounded() {
        return lowerBounded;
    }

    public GenericProcessor<FooPayload> exact() {
        return exact;
    }
}
