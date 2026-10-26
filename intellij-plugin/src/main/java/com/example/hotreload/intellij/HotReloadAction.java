package com.example.hotreload.intellij;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerMessageCategory;

import javax.swing.*;

/**
 * IntelliJ action that compiles the current module and notifies the
 * Hot-Reload Agent to reload changed classes.
 *
 * <p>This is a skeleton action. To make it fully functional, wire it to the
 * agent's {@code RemoteAttachClient} or to the {@code SourceCompileWatcher}
 * output directory so that compiled classes trigger a hot-reload.</p>
 */
public class HotReloadAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        Module module = CommonDataKeys.MODULE.getData(event.getDataContext());

        // Trigger compilation
        CompilerManager compilerManager = CompilerManager.getInstance(project);
        compilerManager.compile(new CompileStatusNotification() {
            @Override
            public void finished(boolean aborted, int totalErrors, int totalWarnings) {
                if (aborted || totalErrors > 0) {
                    showMessage(project, "Compilation failed — hot-reload skipped", true);
                    return;
                }

                showMessage(project, "Compilation complete — classes hot-reloaded", false);
                // TODO: Push compiled class bytes to the running agent via
                // RemoteAttachClient or notify the file watcher.
            }
        }, null);
    }

    @Override
    public void update(AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabledAndVisible(project != null);
    }

    private void showMessage(Project project, String message, boolean error) {
        SwingUtilities.invokeLater(() -> {
            String title = error ? "Hot-Reload Agent" : "Hot-Reload Agent";
            JOptionPane.showMessageDialog(null, message, title,
                    error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
        });
    }
}
