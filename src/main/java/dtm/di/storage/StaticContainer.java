package dtm.di.storage;

import lombok.Getter;

class StaticContainer {

    @Getter
    private static volatile DependencyContainerStorage containerStorage;

    public static synchronized void setContainerStorage(DependencyContainerStorage containerStorage) {
        StaticContainer.containerStorage = containerStorage;
    }

}
