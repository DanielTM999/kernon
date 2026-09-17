package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Qualifier;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Qualifier("explicit")
@Profile("generics-order")
public class QualifiedFooProcessor implements GenericProcessor<FooPayload> {
    @Override
    public String describe() {
        return "qualified-foo-processor";
    }
}
