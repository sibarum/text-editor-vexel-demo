# Git mode: the build plan

Status: **slices 0 to 4 built**, 2026-10-27: the library's read side, the branch in the status line, Git mode,
diffs, and staging. The look is the
[Vex Git Mode design](https://claude.ai/artifact/JFtP9yLvLHnnrcPEcTK3Sg) (2026-10-17): a fourth navigator mode,
the working tree's changes on the left, and history, branches or a file's diff where the editor stands.

Vexplore keeps git to status and staging on purpose, and says a commit box belongs to some other application
([vexplore/docs/ideas/git.md](../../vexplore/docs/ideas/git.md)). This is that application.

## Where each part goes

The three places follow the split the editor already has: what runs git is shared, what decides is `-core`, what
shows is `-gui`.

### vex-suite-common, `dev.sibarum.suite.git`

`Git` runs git as a process with no lock and no prompt, and `Status` and `Porcelain` read `git status`. Git mode
needs more questions and the first writes. Every new reader is pure, bytes in and records out, so it is tested
without git, as `Porcelain` is. `GitTest` covers the commands against a throwaway repository.

| Addition | What it is |
|---|---|
| `Status.Change` gains `index` and `tree` | The two letters of porcelain's `XY`, kept. `State` folds them into one word, which is right for Vexplore's column, but Git mode lists a file in both Unstaged and Staged when it is staged and then changed again. Additive: `State` and the 3-argument constructor stay. |
| `Git.numstat(root, staged)`, `Numstat` | `diff [--cached] --numstat -z`: lines added and removed per path, for the `+4 −1` on each row. An untracked file counts its own lines. |
| `Git.diff(root, path, staged)`, `Diff` | The unified diff of one file, read into hunks of lines, each with its old number, new number, `+`, `−` or ` `, and text. An untracked file's diff is made by reading it, all lines added, not by git: `--no-index` needs the null device's name, which the build's platform check refuses outside an OS module. |
| `Git.log(root, ref, count)`, `Log` | `log -z --format=…`: hash, short hash, author, time, subject and the refs at it (`HEAD`, `origin/main`). |
| `Git.branches(root)`, `Refs` | `for-each-ref` over `refs/heads` and `refs/remotes`, with each branch's upstream and ahead/behind. |
| `Git.run(dir, args, stdin)` | An overload that writes to git's standard input before closing it. Commit takes its message on `-F -` this way, so a message with several lines never passes through Windows' quoting of arguments. |
| `Git.run(…, timeout)` | `TIMEOUT` is 30 s. A commit with hooks or a push may take longer, so the caller chooses. |
| `stage`, `unstage`, `commit`, `push`, `switchTo` | Each returns the whole `Result`, so the console can show what git said. Unstage is `restore --staged`, or `rm --cached` before the first commit. |

### `-core`: a `Repository` component, and git jobs

A component of its own, beside `Editor`, on its own lane. It owns what the editor knows about the repository: the
root it is watching, the latest status, and which refresh is current. Every run of git is a job on the offload lane,
as `ReadFile` is, because a status on a large repository is a slow answer and on a component's lane it would be a
stall that the watchdog ends the application for.

- **Jobs:** `ReadStatus` (status and numstat together, untracked files counted by reading them), `ReadStamp` (the
  2 s look), `ReadDiff`, `ReadLog`, `ReadBranches`, and `RunGit` for every write. Each is listed in `Core`.
- **No lists cross (FN-24), so a list crosses as git's text.** A status is one `GitHead` (branch, upstream, ahead,
  behind, how many unstaged and staged) and one `GitTree`, which carries git's own status and numstat output as
  strings; the view reads them with the shared `Status.parse` and `Numstat.parse`. The plan first had a `GitFile`
  per path, the roll call `Closing` uses. Built, that was wrong twice: a job returns exactly one reply, and a
  mailbox that overflows ends the application, which a repository with thousands of changes would do. A diff crosses
  as its text in the same way, and history and branches will too.
- **Generations.** Each refresh carries a number. A reply from an older refresh is dropped, so a slow status that
  arrives after a quick one never overwrites it.
- **When it refreshes:** on a root change, on entering Git mode, after each of its own writes, after the editor
  saves or makes something (it hears the editor's `Relist`), and from a Refresh button. `Relist` stays a lossless
  mailbox, because the view hears the same topic and one topic has one loss class (T4.1); a burst is folded into
  one more question by the component instead. While Git mode is up the view's clock ticks every 2 s
  (`krono.every`, named `git-watch`), and `ReadStamp` reads the modification times of `index`, `HEAD` and
  `logs/HEAD` in the repository's own directory, so a commit made at a prompt shows up without a click. That look is
  three `stat`s, not a run of git; git is asked only when they moved. It cannot see a file edited outside the editor,
  or a push; Refresh asks outright. While the tick runs the clock never goes quiet, so ottermate's `settle` times
  out in Git mode; read with `find` and `await` there.
- **What it decides:** whether Commit is allowed (something staged and a first line), whether Push is allowed
  (ahead > 0), and what a failed run means. Git's own stderr goes to the console as it came.
- `-core` depends on `vex-suite-common-git`, the library's git package as a module of its own. The whole library
  brings `vexelray-gui-widget`, and `-core` builds against no widget.

### `-gui`: the mode, the pane and the stage

- **`Navigator.Mode.GIT`.** The way in is a branch chip beside the root's name, `main ↑2`, shown only inside a
  repository. It is a toggle button, the pattern the root's name already uses for Open. Ctrl+Shift+G does the same.
  Built in slice 2, with `GitPane` as the card: a row opens its file.
- **The Git pane** (left), drawn as in the design:
  - the branch, with how far it is ahead or behind;
  - **Unstaged** and **Staged** lists, each row showing the status letter, the name, the folder, the line counts
    and a + or − button;
  - stage all and unstage all;
  - the commit message, with an `n / 72` count on its first line;
  - Commit and Push buttons.

  The lists are `ListView`s, so a thousand untracked files do not build a thousand rows.
- **`GitStage`** (right), standing where the editor does, the way `Preview` does in Open. With no file selected it
  shows Branches and Changesets. With a file selected it shows that file's diff: old and new line numbers, the `+`
  or `−`, and colored lines, with Stage or Unstage at the top. Double-clicking a line of the diff opens the file at
  that line. Built in slice 3 as a diff only: a row picked (click or arrows) shows it, the editor stays when none
  is, and Branches and Changesets come in slice 6. A removed line opens at the line that now stands where it was.
  Open file, Close and Escape are at its top; leaving Git mode puts it away. The diff on show is asked for again
  with every new status, through the `Repository`, which sends on only the answer to the latest ask.
- **The status line** gets a Git slot showing the branch in every mode, not just Git.
- Each part has a landmark, so `ottermate` and the tests can find it.

## Slices

Each slice builds, is tested, and works without the next one. The first is in vex-suite-common alone, and every
later one changes only this repository, except where it says otherwise.

| # | Slice | Where | What you can review |
|---|---|---|---|
| 0 | **The read side of the library:** `XY` in `Status`, `Numstat`, `Diff`, `Log`, `Refs`, the queries for each, and the stdin and timeout overloads | vex-suite-common | Pure parser tests and `GitTest`. No change to the IDE. |
| 1 | **Branch in the status line:** `Repository`, `ReadStatus`, `GitHead`, the Git status slot | core, gui | The smallest end-to-end path through component, job, message and view. The status line shows `main ↑2`, and it refreshes after a save. |
| 2 | **Git mode, read-only:** `Mode.GIT`, the branch chip, the Unstaged and Staged lists, Refresh, the `.git/index` check | core, gui | The mode switches in and out. The lists match `git status`. A commit made in a terminal shows up within 2 s. |
| 3 | **Diffs:** `ReadDiff`, the diff view in `GitStage`, opening the file at a line | core, gui | Click a row to see its diff. Covers modified, added, deleted, untracked and renamed files. |
| 4 | **Staging:** the stage and unstage commands (library), the +/− buttons, stage all, results in the console | lib, core, gui | Stage, unstage and partly staged files. Git's errors appear in the console. Built: `RunGit`, writes queued one at a time in the `Repository`, the diff's Stage/Unstage, save-first. Git's error goes to the status line, not the console, which is for runs. |
| 5 | **Commit:** the message box, the 72-character count, commit with hooks and a longer timeout | lib, core, gui | Committing empties Staged and the branch goes one ahead. A failing hook leaves everything as it was and says why. |
| 6 | **History and branches**, read-only on `GitStage` | core, gui | Changesets with `HEAD` and `origin/…` marked, and branches with their ahead/behind. |
| 7 | **Push, and switching branch** | lib, core, gui | Push, and what happens when it fails. Switching branch reloads the open tabs whose files changed on disk. |

Slice 7 is last because it is the only one that reaches the network and the only one that changes files under open
tabs. The editor cannot yet reload a tab whose file changed on disk, and switching branch needs that first.

## Not in this plan

Pull, fetch, merge, rebase, stash, resolving conflicts, and staging part of a file. Conflicted files are listed and
marked, but resolving them is done at a prompt. Each of these can be a later slice once the first eight have been
used for a while.

## Questions

1. **Unsaved work when staging. Decided 2026-10-27: save first**, as Run does. Built in slice 4 as
   `Purpose.STAGE`: the view rolls the tabs a stage covers (the file, or everything under the root for Stage all),
   the editor saves the dirty ones with a file, and the stage is sent only when they have landed; a save that fails
   stages nothing, and the editor says why. Unstaging leaves the disk alone and goes at once.
2. **Push credentials.** `Git` sets `GIT_TERMINAL_PROMPT=0`, so a push that needs a password fails rather than
   waiting at a prompt nobody sees. Git Credential Manager may still open its own window. Either rely on that, or
   accept that push only works with credentials already stored and say so when it fails.
3. **Lists across messages.** The plan sends a message per member (FN-24). If the framework's *Could* lands first,
   so a list can be copied across, slices 2 and 6 get simpler. That fix is outside this plan.
