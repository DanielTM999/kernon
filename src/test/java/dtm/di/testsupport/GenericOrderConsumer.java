package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Inject;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Qualifier;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Profile("generics-order")
public class GenericOrderConsumer {

    @Inject
    @Qualifier("explicit")
    private GenericProcessor<FooPayload> explicitlyQualified;

    @Inject
    private GenericProcessor<Integer> byGenericArgument;

    @Inject
    private GenericProcessor rawProcessor;

    public GenericProcessor<FooPayload> explicitlyQualified() {
        return explicitlyQualified;
    }

    public GenericProcessor<Integer> byGenericArgument() {
        return byGenericArgument;
    }

    public GenericProcessor rawProcessor() {
        return rawProcessor;
    }
}
