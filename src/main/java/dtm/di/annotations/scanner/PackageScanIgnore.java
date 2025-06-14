package dtm.di.annotations.scanner;

import java.lang.annotation.*;

/**
 * Annotation para indicar pacotes que devem ser ignorados durante o processo
 * de scan de classes em um container de injeção de dependências ou framework similar.
 *
 * <p>Essa annotation pode ser repetida na mesma classe para ignorar múltiplos pacotes,
 * graças à anotação {@link PackageScansIgnore} que funciona como container.</p>
 *
 * <p><b>Parâmetros:</b></p>
 * <ul>
 *   <li><b>ignorePackage:</b> array de nomes de pacotes que devem ser ignorados no scan.</li>
 *   <li><b>scanType:</b> define o tipo de scan que será realizado. Pode ser:
 *     <ul>
 *       <li>INCREMENT - ignora os pacotes adicionando à lista existente;</li>
 *       <li>REPLACE - substitui a lista atual de pacotes ignorados por essa nova lista.</li>
 *     </ul>
 *   </li>
 *   <li><b>scanElement:</b> nome do elemento de scan (geralmente "default" ou "jar").</li>
 * </ul>
 *
 * <p>Essa annotation é processada em tempo de execução.</p>
 */
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
