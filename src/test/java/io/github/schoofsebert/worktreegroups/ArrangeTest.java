package io.github.schoofsebert.worktreegroups;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArrangeTest {
    private static List<String> arrange(List<String> items, Map<String, String> groupOf) {
        return WorktreeAwareProjectListProvider.arrange(items, groupOf::get, (name, members) -> name + members);
    }

    @Test
    void groupTakesThePlaceOfItsMostRecentMember() {
        assertEquals(
            List.of("a", "jolt worktrees[w1, w2]", "jolt", "b"),
            arrange(List.of("a", "w1", "jolt", "w2", "b"), Map.of("w1", "jolt worktrees", "w2", "jolt worktrees")));
    }

    @Test
    void groupsKeepTheirOwnPlaces() {
        assertEquals(
            List.of("ppl worktrees[p1]", "ppl", "jolt worktrees[j1, j2]", "x"),
            arrange(List.of("p1", "ppl", "j1", "x", "j2"), Map.of("p1", "ppl worktrees", "j1", "jolt worktrees", "j2", "jolt worktrees")));
    }

    @Test
    void nothingGroupedKeepsTheList() {
        assertEquals(List.of("a", "b"), arrange(List.of("a", "b"), Map.of()));
    }
}
