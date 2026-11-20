package io.github.schoofsebert.worktreegroups;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemovedProjectCloserTest {
    @TempDir
    Path home;

    @Test
    void removedFolderUnderHomeIsMissing() {
        assertTrue(RemovedProjectCloser.isRemoved(home.resolve("gone").toString(), home, false));
    }

    @Test
    void existingFolderIsNotMissing() throws IOException {
        assertFalse(RemovedProjectCloser.isRemoved(Files.createDirectory(home.resolve("here")).toString(), home, true));
    }

    @Test
    void foldersOutsideHomeAreLeftAlone() {
        assertFalse(RemovedProjectCloser.isRemoved("/Volumes/unplugged/project", home, true));
        assertFalse(RemovedProjectCloser.isRemoved(home.toString(), home, true));
    }

    @Test
    void onlyDeletesAtOrAboveTheProjectCount() {
        assertTrue(RemovedProjectCloser.isAtOrAbove("/u/.t3/worktrees/spr/a", "/u/.t3/worktrees/spr/a"));
        assertTrue(RemovedProjectCloser.isAtOrAbove("/u/.t3/worktrees/spr", "/u/.t3/worktrees/spr/a"));
        assertFalse(RemovedProjectCloser.isAtOrAbove("/u/.t3/worktrees/spr/a/node_modules", "/u/.t3/worktrees/spr/a"));
        assertFalse(RemovedProjectCloser.isAtOrAbove("/u/.t3/worktrees/spr/ab", "/u/.t3/worktrees/spr/a"));
        assertFalse(RemovedProjectCloser.isAtOrAbove("/u/.t3/worktrees/spr/a", "/u/.t3/worktrees/spr/ab"));
    }

    @Test
    void leftoverOfARemovedWorktreeIsRemoved() throws IOException {
        Path leftover = Files.createDirectories(home.resolve("wt/.idea"));
        Files.writeString(leftover.resolve("workspace.xml"), "<project/>");
        assertTrue(RemovedProjectCloser.isRemoved(home.resolve("wt").toString(), home, true));
    }

    @Test
    void freshProjectThatNeverHadGitStaysOpen() throws IOException {
        Path idea = Files.createDirectories(home.resolve("new/.idea"));
        Files.writeString(idea.resolve("workspace.xml"), "<project/>");
        assertFalse(RemovedProjectCloser.isRemoved(home.resolve("new").toString(), home, false));
    }
}
