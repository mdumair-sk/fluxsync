package org.gradle.accessors.dm;

import org.gradle.api.NonNullApi;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.internal.artifacts.dependencies.ProjectDependencyInternal;
import org.gradle.api.internal.artifacts.DefaultProjectDependencyFactory;
import org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import org.gradle.api.internal.catalog.DelegatingProjectDependency;
import org.gradle.api.internal.catalog.TypeSafeProjectDependencyFactory;
import javax.inject.Inject;

@NonNullApi
public class CoreProjectDependency extends DelegatingProjectDependency {

    @Inject
    public CoreProjectDependency(TypeSafeProjectDependencyFactory factory, ProjectDependencyInternal delegate) {
        super(factory, delegate);
    }

    /**
     * Creates a project dependency on the project at path ":core:protocol"
     */
    public Core_ProtocolProjectDependency getProtocol() { return new Core_ProtocolProjectDependency(getFactory(), create(":core:protocol")); }

    /**
     * Creates a project dependency on the project at path ":core:resumability"
     */
    public Core_ResumabilityProjectDependency getResumability() { return new Core_ResumabilityProjectDependency(getFactory(), create(":core:resumability")); }

    /**
     * Creates a project dependency on the project at path ":core:transfer-engine"
     */
    public Core_TransferEngineProjectDependency getTransferEngine() { return new Core_TransferEngineProjectDependency(getFactory(), create(":core:transfer-engine")); }

}
