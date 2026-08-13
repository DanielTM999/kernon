package dtm.di.testsupport;

public class MainPingEvent {

    private final String message;

    public MainPingEvent(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
