package io.github.schoofsebert.worktreegroups;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.ProjectGroup;
import com.intellij.ide.RecentProjectsManager;
import com.intellij.ide.RecentProjectsManagerBase;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationActivationListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.wm.IdeFrame;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drops recent projects whose folder is gone and files git worktrees into a collapsed
 * "&lt;repo&gt; worktrees" group per main repository.
 */
public final class RecentProjectsTidier implements ApplicationActivationListener, AppLifecycleListener {
    static final String GROUP_SUFFIX = " worktrees";
    private static final String OWNED_GROUPS = "io.github.schoofsebert.worktreeGroups.ownedGroups";
    private static final Logger LOG = Logger.getInstance(RecentProjectsTidier.class);
    // gitdir of a linked worktree: <main>/.git/worktrees/<name>, or <bare>.git/worktrees/<name>
    private static final Pattern WORKTREE_GITDIR = Pattern.compile("^(.+?)(?:/\\.git)?/worktrees/[^/]+/?$");
    private static final AtomicBoolean running = new AtomicBoolean();
    private static final AtomicBoolean rerun = new AtomicBoolean();

    @Override
    public void applicationActivated(@NotNull IdeFrame ideFrame) {
        schedule();
    }

    @Override
    public void welcomeScreenDisplayed() {
        schedule();
    }

    @Override
    public void projectFrameClosed() {
        schedule();
    }

    private static void schedule() {
        rerun.set(true);
        if (!running.compareAndSet(false, true)) return;
        rerun.set(false);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Plan plan = plan(RecentProjectsManagerBase.getInstanceEx().getRecentPaths(), openProjects());
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        if (!plan.isEmpty()) apply(plan);
                    } catch (Throwable t) {
                        LOG.warn("tidying recent projects failed", t);
                    } finally {
                        finish();
                    }
                });
            } catch (Throwable t) {
                LOG.warn("tidying recent projects failed", t);
                finish();
            }
        });
    }

    /** Clears the busy flag only after apply, and catches up on events that came in meanwhile. */
    private static void finish() {
        running.set(false);
        if (rerun.get()) schedule();
    }

    private static Set<Path> openProjects() {
        Set<Path> open = new HashSet<>();
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            String base = project.getBasePath();
            if (base == null) continue;
            try {
                open.add(Path.of(base).normalize());
            } catch (RuntimeException ignored) {
            }
        }
        return open;
    }

    record Plan(List<String> gone, Map<String, String> groupFor) {
        boolean isEmpty() {
            return gone.isEmpty() && groupFor.isEmpty();
        }
    }

    /** File system checks only, off the EDT. */
    static Plan plan(List<String> recentPaths, Set<Path> openProjects) {
        return plan(recentPaths, openProjects, Path.of(System.getProperty("user.home")), PathManager.getConfigDir(), PathManager.getSystemDir());
    }

    static Plan plan(List<String> recentPaths, Set<Path> openProjects, Path home, Path config, Path system) {
        List<String> gone = new ArrayList<>();
        Map<String, String> groupFor = new LinkedHashMap<>();
        for (String recent : recentPaths) {
            try {
                Path path = Path.of(recent).normalize();
                // IDE-internal entries (light-edit) live in config/system dirs and may not exist on disk
                if (path.startsWith(config) || path.startsWith(system) || !path.startsWith(home)) continue;
                Path gitRoot = gitRootOf(path, home);
                if (!openProjects.contains(path) && isGone(path, gitRoot)) {
                    gone.add(recent);
                    continue;
                }
                String main = gitRoot == null ? null : mainRepoOf(gitRoot);
                if (main != null) groupFor.put(recent, Path.of(main).getFileName().toString().replaceFirst("\\.git$", "") + GROUP_SUFFIX);
            } catch (RuntimeException e) {
                LOG.debug("skipping recent project " + recent, e);
            }
        }
        return new Plan(gone, groupFor);
    }

    /**
     * Missing, or what a removed worktree leaves behind: the IDE recreates .idea/workspace.xml when it
     * saves a project whose folder was deleted. A real project's .idea always holds more than that.
     */
    static boolean isGone(Path path, Path gitRoot) {
        // notExists is false when access is denied: only a confirmed missing folder counts
        if (Files.notExists(path)) return true;
        if (gitRoot != null || !Files.isDirectory(path)) return false;
        try (var top = Files.list(path); var idea = Files.list(path.resolve(".idea"))) {
            return top.allMatch(e -> e.getFileName().toString().startsWith("."))
                && idea.allMatch(e -> e.getFileName().toString().equals("workspace.xml"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Nearest folder at or above {@code path} (up to home) that has a .git entry. */
    static Path gitRootOf(Path path, Path home) {
        for (Path dir = path; dir != null && dir.startsWith(home) && !dir.equals(home); dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) return dir;
        }
        return null;
    }

    /** Main repository of a linked git worktree, or null if {@code dir} isn't one. */
    static String mainRepoOf(Path dir) {
        Path dotGit = dir.resolve(".git");
        if (!Files.isRegularFile(dotGit)) return null;
        String first;
        try (var lines = Files.lines(dotGit)) {
            first = lines.findFirst().orElse("");
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (!first.startsWith("gitdir:")) return null;
        Path gitDir = dir.resolve(first.substring("gitdir:".length()).trim()).normalize();
        // submodules point to .git/modules/<name> and don't match
        Matcher m = WORKTREE_GITDIR.matcher(gitDir.toString());
        return m.matches() ? m.group(1) : null;
    }

    /** Mutations on the EDT. */
    private static void apply(Plan plan) {
        RecentProjectsManager manager = RecentProjectsManager.getInstance();
        // a project may have opened since planning
        Set<Path> open = openProjects();
        for (String path : plan.gone()) {
            if (manager.hasPath(path) && !open.contains(Path.of(path).normalize())) manager.removePath(path);
        }

        PropertiesComponent properties = PropertiesComponent.getInstance();
        Set<String> owned = new LinkedHashSet<>(properties.getList(OWNED_GROUPS) == null ? List.of() : properties.getList(OWNED_GROUPS));
        for (var e : plan.groupFor().entrySet()) {
            String path = e.getKey();
            if (!manager.hasPath(path)) continue;
            // respect projects someone filed into a group by hand
            if (manager.getGroups().stream().anyMatch(g -> g.getProjects().contains(path))) continue;
            ProjectGroup group = manager.getGroups().stream()
                .filter(g -> g.getName().equals(e.getValue()))
                .findFirst()
                .orElseGet(() -> {
                    ProjectGroup created = new ProjectGroup(e.getValue());
                    created.setExpanded(false);
                    manager.addGroup(created);
                    owned.add(created.getName());
                    return created;
                });
            boolean expanded = group.isExpanded();
            manager.moveProjectToGroup(path, group);
            group.setExpanded(expanded); // moveProjectToGroup always expands
        }

        // removeGroup also drops the group's projects from the list: only ever remove our own, empty ones
        for (ProjectGroup group : List.copyOf(manager.getGroups())) {
            if (owned.contains(group.getName()) && group.getProjects().isEmpty()) {
                manager.removeGroup(group);
                owned.remove(group.getName());
            }
        }
        owned.retainAll(manager.getGroups().stream().map(ProjectGroup::getName).toList());
        properties.setList(OWNED_GROUPS, owned);
    }
}
