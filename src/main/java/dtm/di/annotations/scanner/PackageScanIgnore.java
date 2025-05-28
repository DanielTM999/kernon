package dtm.di.annotations.scanner;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(PackageScansIgnore.class)
public @interface PackageScanIgnore {

    String[] ignorePackage() default {};
    ScanType scanType() default ScanType.INCREMENT;
    String scanElement() default "default";

    public enum ScanType{
        INCREMENT,
        REPLACE
    }

}
