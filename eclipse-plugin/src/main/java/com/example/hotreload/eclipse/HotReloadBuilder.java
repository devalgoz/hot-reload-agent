package com.example.hotreload.eclipse;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import java.util.Map;

/**
 * Eclipse builder that compiles changed sources and notifies the Hot-Reload
 * Agent.
 *
 * <p>This is a skeleton builder. To make it fully functional, wire it to push
 * compiled class bytes to a running Hot-Reload Agent.</p>
 */
public class HotReloadBuilder extends IncrementalProjectBuilder {

    @Override
    protected IProject[] build(int kind, IProgressMonitor monitor, Map<String, String> args)
            throws CoreException {
        if (kind == FULL_BUILD) {
            fullBuild(monitor);
        } else {
            IResourceDelta delta = getDelta(getProject());
            if (delta == null) {
                fullBuild(monitor);
            } else {
                incrementalBuild(delta, monitor);
            }
        }
        return null;
    }

    protected void fullBuild(final IProgressMonitor monitor) throws CoreException {
        try {
            getProject().build(IncrementalProjectBuilder.FULL_BUILD, monitor);
        } catch (CoreException e) {
            throw e;
        }
        notifyAgent();
    }

    protected void incrementalBuild(IResourceDelta delta, IProgressMonitor monitor)
            throws CoreException {
        // Collect changed .java files
        final java.util.List<IResource> changedJava = new java.util.ArrayList<>();
        delta.accept(new IResourceDeltaVisitor() {
            public boolean visit(IResourceDelta delta) throws CoreException {
                IResource resource = delta.getResource();
                if (resource.getType() == IResource.FILE
                        && resource.getFileExtension() != null
                        && resource.getFileExtension().equals("java")) {
                    switch (delta.getKind()) {
                        case IResourceDelta.ADDED:
                        case IResourceDelta.CHANGED:
                            changedJava.add(resource);
                            break;
                        default:
                            break;
                    }
                }
                return true;
            }
        });

        try {
            getProject().build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
        } catch (CoreException e) {
            throw e;
        }

        if (!changedJava.isEmpty()) {
            notifyAgent();
        }
    }

    private void notifyAgent() {
        // TODO: Push compiled class bytes to the running Hot-Reload Agent
        // via RemoteAttachClient or file watcher notification.
    }
}
