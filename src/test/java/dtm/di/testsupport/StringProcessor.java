package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Profile("generics")
public class StringProcessor extends BaseGenericProcessor<String> {
    @Override
    public String describe() {
        return "string-processor";
    }
}
