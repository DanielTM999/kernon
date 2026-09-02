package dtm.di.external;

import dtm.di.testsupport.PerfFixtures;
import dtm.di.testsupport.PerfJar;
import dtm.discovery.finder.simple.ClassFinderProjectService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

@Tag("performance")
class ScanProbeTest {

    @Test
    void probeSizes() {
        int[][] shapes = {{2, 5}, {3, 20}, {5, 40}};

        for (int[] shape : shapes) {
            int total = shape[0] * shape[1];
            try (PerfJar jar = PerfJar.build("probe" + total, PerfFixtures.sources(shape[0], shape[1], 2))) {
                long start = System.nanoTime();
                Set<Class<?>> found = new ClassFinderProjectService().loadByDirectory(jar.folder());
                long millis = (System.nanoTime() - start) / 1_000_000;
                System.out.println("RESULT services=" + total + " found=" + found.size() + " millis=" + millis);
            }
        }
    }
}
