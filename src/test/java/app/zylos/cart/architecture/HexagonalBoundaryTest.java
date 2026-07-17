package app.zylos.cart.architecture;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "app.zylos.cart", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalBoundaryTest {

    @ArchTest
    static final ArchRule hexagonal_layers_are_respected = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Domain")
            .definedBy("app.zylos.cart.domain..")
            .layer("Application")
            .definedBy("app.zylos.cart.application..")
            .layer("Adapters")
            .definedBy("app.zylos.cart.adapter..")
            .whereLayer("Adapters")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Application")
            .mayOnlyBeAccessedByLayers("Adapters")
            .whereLayer("Domain")
            .mayOnlyBeAccessedByLayers("Application", "Adapters");
}
