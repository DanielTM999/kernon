package dtm.di.storage.external;

import dtm.di.prototypes.Dependency;

public record GenericRegistrationSlot(
        String genericKey,
        String qualifier,
        Dependency dependency
) {
}
