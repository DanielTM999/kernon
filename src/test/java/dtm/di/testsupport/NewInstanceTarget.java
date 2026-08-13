package dtm.di.testsupport;

import dtm.di.annotations.Inject;

public class NewInstanceTarget {

    @Inject
    private SharedGreeter greeter;

    @Inject
    private MainCounter counter;

    public SharedGreeter greeter() {
        return greeter;
    }

    public MainCounter counter() {
        return counter;
    }

    public String describe() {
        return greeter == null ? "sem-externo" : greeter.greet();
    }
}
