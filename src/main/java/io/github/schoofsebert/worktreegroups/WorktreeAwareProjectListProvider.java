package io.github.schoofsebert.worktreegroups;

import com.intellij.icons.AllIcons;
import com.intellij.ide.ProjectGroup;
import com.intellij.ide.RecentProjectListActionProvider;
import com.intellij.ide.RecentProjectsManagerBase;
import com.intellij.ide.ReopenProjectAction;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;

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

public class WorktreeAwareProjectListProvider extends RecentProjectListActionProvider {
    private static final Logger LOG = Logger.getInstance(WorktreeAwareProjectListProvider.class);

    // only the toolbar switcher asks for a flat list
    @Override
    public @NotNull List<AnAction> getActions(boolean addClearListItem, boolean useGroups, boolean allowCustomProjectActions) {
        List<AnAction> actions = super.getActions(addClearListItem, useGroups, allowCustomProjectActions);
        if (useGroups) return actions;
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
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            String base = project.getBasePath();
            if (base != null) open.add(FileUtil.toSystemIndependentName(base));
        }
        return open;
    }

    private static AnAction submenu(String name, List<AnAction> members) {
        return new WorktreeGroup(name, members.stream()
            .map(m -> m instanceof ReopenProjectAction r ? new OpenWorktree(r) : m)
            .toList());
    }

    private static final class WorktreeGroup extends DefaultActionGroup implements DumbAware {
        WorktreeGroup(String name, List<AnAction> members) {
            super(name, members);
            setPopup(true);
            getTemplatePresentation().setIcon(AllIcons.Nodes.Folder);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().putClientProperty(ActionUtil.SECONDARY_TEXT, String.valueOf(getChildrenCount()));
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    // plain submenu renderer would show the repo name for each
    private static final class OpenWorktree extends DumbAwareAction {
        private final ReopenProjectAction target;

        OpenWorktree(ReopenProjectAction target) {
            super(Path.of(target.getProjectPath()).getFileName().toString(), FileUtil.toSystemDependentName(target.getProjectPath()), null);
            this.target = target;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setIcon(target.getProjectIcon());
            e.getPresentation().putClientProperty(ActionUtil.SECONDARY_TEXT, target.getBranchName());
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            ActionUtil.performAction(target, e);
        }
    }
}
