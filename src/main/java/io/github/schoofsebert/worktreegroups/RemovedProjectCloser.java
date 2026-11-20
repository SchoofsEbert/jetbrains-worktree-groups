package io.github.schoofsebert.worktreegroups;

import com.intellij.openapi.application.ApplicationActivationListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.wm.IdeFrame;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RemovedProjectCloser implements BulkFileListener, ApplicationActivationListener {
    private static final Logger LOG = Logger.getInstance(RemovedProjectCloser.class);
    private static final AtomicBoolean scheduled = new AtomicBoolean();
    private static final Set<String> gitProjects = ConcurrentHashMap.newKeySet();

    @Override
    public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
        List<String> deleted = events.stream().filter(e -> e instanceof VFileDeleteEvent).map(VFileEvent::getPath).toList();
        if (deleted.isEmpty()) return;
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            String base = project.getBasePath();
            if (base != null && deleted.stream().anyMatch(d -> isAtOrAbove(d, base))) {
                closeRemoved();
                return;
            }
        }
    }

    static boolean isAtOrAbove(String deleted, String base) {
        return base.equals(deleted) || base.startsWith(deleted.endsWith("/") ? deleted : deleted + "/");
    }

    @Override
    public void applicationActivated(@NotNull IdeFrame ideFrame) {
        closeRemoved();
    }

    private static void closeRemoved() {
        if (!scheduled.compareAndSet(false, true)) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            scheduled.set(false);
            Path home = Path.of(System.getProperty("user.home"));
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                String base = project.getBasePath();
                try {
                    if (base == null || project.isDisposed() || !project.isOpen()) continue;
                    if (isRemoved(base, home, gitProjects.contains(base))) {
                        gitProjects.remove(base);
                        // saving would recreate .idea/workspace.xml in the removed folder
                        ProjectManagerEx.getInstanceEx().forceCloseProject(project, false);
                    } else if (RecentProjectsTidier.gitRootOf(Path.of(base), home) != null) {
                        gitProjects.add(base);
                    }
                } catch (RuntimeException e) {
                    LOG.warn("closing removed project " + project.getName() + " failed", e);
                }
            }
        }, ModalityState.nonModal());
    }

    // under home only: a project on an unplugged drive is not removed
    // hadGit: the IDE may already have saved .idea/workspace.xml into the removed folder
    static boolean isRemoved(String basePath, Path home, boolean hadGit) {
        Path path = Path.of(basePath).normalize();
        if (!path.startsWith(home) || path.equals(home)) return false;
        if (Files.notExists(path)) return true;
        return hadGit && RecentProjectsTidier.gitRootOf(path, home) == null && RecentProjectsTidier.isGone(path, null);
    }
}
