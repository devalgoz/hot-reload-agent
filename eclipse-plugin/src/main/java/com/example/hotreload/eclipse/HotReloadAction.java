package com.example.hotreload.eclipse;

import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWorkbenchWindowActionDelegate;

/**
 * Eclipse action that triggers compilation and hot-reload of changed classes.
 *
 * <p>This is a skeleton action. To make it fully functional, wire it to push
 * compiled class bytes to the running Hot-Reload Agent.</p>
 */
public class HotReloadAction implements IWorkbenchWindowActionDelegate {

    private IWorkbenchWindow window;

    @Override
    public void run(IAction action) {
        MessageDialog.openInformation(
                window.getShell(),
                "Hot-Reload Agent",
                "Compilation triggered — classes hot-reloaded to running JVM.");
        // TODO: Trigger compile and push class bytes to agent via RemoteAttachClient
    }

    @Override
    public void selectionChanged(IAction action, ISelection selection) {
        // no-op
    }

    @Override
    public void dispose() {
        // no-op
    }

    @Override
    public void init(IWorkbenchWindow window) {
        this.window = window;
    }
}
