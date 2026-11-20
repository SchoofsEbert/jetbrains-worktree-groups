# Worktree Groups

A JetBrains IDE plugin (IntelliJ IDEA, WebStorm, PyCharm, … 2026.2+) that keeps **Recent Projects** usable when you work with many git worktrees, for example with AI coding agents.

- Each linked worktree goes into a collapsed `<repo> worktrees` group; main repositories stay on top.
- The project switcher in the toolbar shows each group as one submenu, at the spot of its most recently used worktree. Open worktrees stay under Open Projects.
- Projects whose folder is gone are removed from the list. That includes folders a removed worktree left behind: the IDE recreates `.idea/workspace.xml` when it saves a project whose folder was deleted.

It runs on startup, when the IDE window gets focus, and when a project closes. It only changes the Recent Projects list, through the IDE's own API: it never creates or deletes files.

What it leaves alone:

- open projects, and projects outside your home folder or in the IDE's own folders;
- projects you put in a group yourself;
- groups it didn't create.

## Install

Add this custom plugin repository once per IDE, under **Settings → Plugins → ⚙ → Manage Plugin Repositories**:

```
https://github.com/SchoofsEbert/jetbrains-worktree-groups/releases/latest/download/updatePlugins.xml
```

Then install **Worktree Groups** from the Marketplace tab. New releases show up as regular plugin updates.

## Development

```
./gradlew build          # compile and run tests
./gradlew verifyPlugin   # check compatibility with IntelliJ IDEA 2026.2 and WebStorm 2026.3
./gradlew runIde         # try it in a sandbox IDE
```

Every push to `main` builds, tests, verifies and publishes release `1.0.<run number>`. Work on feature branches and merge through pull requests.
