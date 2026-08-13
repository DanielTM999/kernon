package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.event.Event;
import dtm.di.annotations.event.EventListener;

@Component
@Event
public class MainPrototypeListener {

    @EventListener
    public void onPing(MainPingEvent event) {
        Probe.record("MainPrototypeListener:" + event.message() + ":" + System.identityHashCode(this));
    }
}
