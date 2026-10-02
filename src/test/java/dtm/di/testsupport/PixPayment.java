package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Qualifier;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Qualifier("pix")
@Profile("beanmap")
public class PixPayment implements PaymentMethod {
    @Override
    public String name() {
        return "pix";
    }
}
