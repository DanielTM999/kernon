package dtm.di.storage.external;

import dtm.di.prototypes.Dependency;

public record DependencyRegistrationSlot(
        Class<?> indexedType,
        String qualifier,
        Dependency dependency
) {
}
