package com.studentprep;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModulithArchitectureTest {

    ApplicationModules modules = ApplicationModules.of(StudentPrepApplication.class);

    @Test
    void verifyModulithArchitecture() {
        modules.verify();
    }

    @Test
    void createModuleDocumentation() {
        new Documenter(modules)
            .writeModulesAsPlantUml()
            .writeIndividualModulesAsPlantUml();
    }
}
