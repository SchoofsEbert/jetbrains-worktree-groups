package io.github.schoofsebert.worktreegroups;

import com.intellij.ide.ProjectGroup;
import com.intellij.ide.RecentProjectListActionProvider;
import com.intellij.ide.RecentProjectsManagerBase;
import com.intellij.ide.ReopenProjectAction;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Toolbar project switcher: shows each "&lt;repo&gt; worktrees" group as one submenu, placed where its
 * most recently used worktree would be. The switcher ignores Recent Projects groups otherwise.
 */
public class WorktreeAwareProjectListProvider extends RecentProjectListActionProvider {
    private static final Logger LOG = Logger.getInstance(WorktreeAwareProjectListProvider.class);

    @Override
    public @NotNull List<AnAction> getActions(@Nullable Project project) {
        List<AnAction> actions = super.getActions(project);
        try {
            Map<String, String> groupOf = worktreeGroups();
            if (groupOf.isEmpty()) return actions;
            Set<String> open = openPaths();
            return arrange(actions,
                a -> a instanceof ReopenProjectAction r && !open.contains(r.getProjectPath()) ? groupOf.get(r.getProjectPath()) : null,
                WorktreeAwareProjectListProvider::submenu);
        } catch (RuntimeException e) {
            LOG.warn("grouping worktrees in the project switcher failed", e);
            return actions;
        }
    }

    /**
     * Replaces the members of each group by a single submenu at the first member's position;
     * keeps the order of everything else, and of the members inside the submenu.
     */
    static <T> List<T> arrange(List<T> items, Function<T, String> groupOf, BiFunction<String, List<T>, T> submenu) {
        Map<String, List<T>> members = new LinkedHashMap<>();
        for (T item : items) {
            String group = groupOf.apply(item);
            if (group != null) members.computeIfAbsent(group, g -> new ArrayList<>()).add(item);
        }
        List<T> result = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (T item : items) {
            String group = groupOf.apply(item);
            if (group == null) result.add(item);
            else if (placed.add(group)) result.add(submenu.apply(group, members.get(group)));
        }
        return result;
    }

    /** Project path → name, for the groups the tidier created. */
    private static Map<String, String> worktreeGroups() {
        List<String> owned = PropertiesComponent.getInstance().getList(RecentProjectsTidier.OWNED_GROUPS);
        Map<String, String> groupOf = new HashMap<>();
        if (owned == null) return groupOf;
        for (ProjectGroup group : RecentProjectsManagerBase.getInstanceEx().getGroups()) {
            if (!owned.contains(group.getName())) continue;
            for (String path : group.getProjects()) groupOf.put(path, group.getName());
        }
        return groupOf;
    }

    private static Set<String> openPaths() {
        Set<String> open = new HashSet<>();
        RecentProjectsManagerBase manager = RecentProjectsManagerBase.getInstanceEx();
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            Path path = manager.getProjectPath(project);
            if (path != null) open.add(FileUtil.toSystemIndependentName(path.toString()));
        }
        return open;
    }

    private static AnAction submenu(String name, List<AnAction> members) {
        DefaultActionGroup group = new DefaultActionGroup(name, true);
        for (AnAction member : members) {
            group.add(member instanceof ReopenProjectAction r ? new OpenWorktree(r) : member);
        }
        return group;
    }

    /** Submenus use the plain popup renderer, which would show the repo name for every worktree: show the folder. */
    private static final class OpenWorktree extends DumbAwareAction {
        private final ReopenProjectAction target;

        OpenWorktree(ReopenProjectAction target) {
            super(Path.of(target.getProjectPath()).getFileName().toString(), FileUtil.toSystemDependentName(target.getProjectPath()), null);
            this.target = target;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            ActionUtil.invokeAction(target, e.getDataContext(), e.getPlace(), e.getInputEvent(), null);
        }
    }
}
