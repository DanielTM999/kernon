package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Profile("generics")
public class FooProcessor implements GenericProcessor<FooPayload> {
    @Override
    public String describe() {
        return "foo-processor";
    }
}
