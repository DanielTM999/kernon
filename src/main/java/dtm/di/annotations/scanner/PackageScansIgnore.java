package dtm.di.annotations.scanner;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PackageScansIgnore {
    PackageScanIgnore[] value();
}
