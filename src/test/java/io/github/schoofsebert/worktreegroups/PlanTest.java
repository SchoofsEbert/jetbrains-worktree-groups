package io.github.schoofsebert.worktreegroups;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanTest {
    @TempDir
    Path home;
    Path config;
    Path system;

    @BeforeEach
    void setUp() throws IOException {
        config = Files.createDirectories(home.resolve("Library/Application Support/JetBrains/IntelliJIdea2026.2"));
        system = Files.createDirectories(home.resolve("Library/Caches/JetBrains/IntelliJIdea2026.2"));
    }

    private RecentProjectsTidier.Plan plan(Set<Path> open, Path... paths) {
        return RecentProjectsTidier.plan(List.of(paths).stream().map(Path::toString).toList(), open, home, config, system);
    }

    private RecentProjectsTidier.Plan plan(Path... paths) {
        return plan(Set.of(), paths);
    }

    private Path dir(String relative) throws IOException {
        return Files.createDirectories(home.resolve(relative));
    }

    private Path file(String relative, String content) throws IOException {
        Path file = home.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private Path repo(String relative) throws IOException {
        Path repo = dir(relative);
        dir(relative + "/.git");
        file(relative + "/README.md", "x");
        return repo;
    }

    private Path worktree(String relative, String gitdir) throws IOException {
        file(relative + "/.git", "gitdir: " + gitdir + "\n");
        file(relative + "/README.md", "x");
        return home.resolve(relative);
    }

    @Test
    void missingFolderIsGone() {
        Path missing = home.resolve("worktrees/repo/feature");
        assertEquals(List.of(missing.toString()), plan(missing).gone());
    }

    @Test
    void removedWorktreeLeftoverIsGone() throws IOException {
        file("worktrees/repo/a/.idea/workspace.xml", "<project/>");
        file("worktrees/repo/b/.idea/workspace.xml", "<project/>");
        file("worktrees/repo/b/.gitignore", "");
        dir("worktrees/repo/b/.illuminatedCloud/lwc");
        Path a = home.resolve("worktrees/repo/a");
        Path b = home.resolve("worktrees/repo/b");
        assertEquals(List.of(a.toString(), b.toString()), plan(a, b).gone());
    }

    @Test
    void realProjectsAreKept() throws IOException {
        file("fresh/.idea/workspace.xml", "<project/>");
        file("fresh/.idea/misc.xml", "<project/>");
        file("dotfiles/.zshrc", "");
        Path empty = dir("empty");
        file("plain/index.js", "");
        repo("repo");
        file("repo/sub/.idea/workspace.xml", "<project/>");
        var plan = plan(home.resolve("fresh"), home.resolve("dotfiles"), empty, home.resolve("plain"), home.resolve("repo/sub"));
        assertEquals(List.of(), plan.gone());
        assertEquals(Map.of(), plan.groupFor());
    }

    @Test
    void openProjectIsNeverGone() {
        Path missing = home.resolve("gone");
        assertEquals(List.of(), plan(Set.of(missing), missing).gone());
    }

    @Test
    void entriesOutsideHomeOrInIdeDirsAreIgnored() {
        Path lightEdit = config.resolve("light-edit");
        Path cached = system.resolve("something");
        Path outside = Path.of("/definitely/not/here");
        var plan = plan(lightEdit, cached, outside);
        assertEquals(List.of(), plan.gone());
        assertEquals(Map.of(), plan.groupFor());
    }

    @Test
    void worktreesAreGroupedByMainRepository() throws IOException {
        Path main = repo("code/spr-lux");
        Path absolute = worktree("worktrees/spr-lux/feature", main.resolve(".git/worktrees/feature").toString());
        Path relative = worktree("code/spr-lux-fix", "../spr-lux/.git/worktrees/spr-lux-fix");
        Path bare = worktree("worktrees/tool/x", home.resolve("bare/tool.git/worktrees/x").toString());
        var plan = plan(main, absolute, relative, bare);
        assertEquals(Map.of(
            absolute.toString(), "spr-lux worktrees",
            relative.toString(), "spr-lux worktrees",
            bare.toString(), "tool worktrees"), plan.groupFor());
        assertEquals(List.of(), plan.gone());
    }

    @Test
    void worktreeOpenedAtSubfolderIsGrouped() throws IOException {
        Path main = repo("code/app");
        Path wt = worktree("worktrees/app/feature", main.resolve(".git/worktrees/feature").toString());
        Path ui = dir("worktrees/app/feature/ui");
        file("worktrees/app/feature/ui/package.json", "{}");
        assertEquals(Map.of(ui.toString(), "app worktrees"), plan(ui).groupFor());
        assertEquals(Map.of(wt.toString(), "app worktrees"), plan(wt).groupFor());
    }

    @Test
    void submodulesAreNotWorktrees() throws IOException {
        Path main = repo("code/app");
        Path sub = worktree("code/app/lib", main.resolve(".git/modules/lib").toString());
        assertEquals(Map.of(), plan(sub).groupFor());
    }

    @Test
    void malformedEntryDoesNotStopTheRest() {
        Path missing = home.resolve("gone");
        var plan = RecentProjectsTidier.plan(List.of("bad\0path", missing.toString()), Set.of(), home, config, system);
        assertEquals(List.of(missing.toString()), plan.gone());
    }
}
