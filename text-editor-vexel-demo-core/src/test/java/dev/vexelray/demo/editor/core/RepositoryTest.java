package dev.vexelray.demo.editor.core;

import dev.sibarum.suite.git.Diff;
import dev.vexelray.demo.editor.core.Messages.CommitStaged;
import dev.vexelray.demo.editor.core.Messages.DiffAsk;
import dev.vexelray.demo.editor.core.Messages.GitCommitted;
import dev.vexelray.demo.editor.core.Messages.GitPushed;
import dev.vexelray.demo.editor.core.Messages.PushBranch;
import dev.vexelray.demo.editor.core.Messages.Reload;
import dev.vexelray.demo.editor.core.Messages.SwitchBranch;
import dev.vexelray.demo.editor.core.Messages.DiffRead;
import dev.vexelray.demo.editor.core.Messages.GitAsk;
import dev.vexelray.demo.editor.core.Messages.GitDiff;
import dev.vexelray.demo.editor.core.Messages.GitOp;
import dev.vexelray.demo.editor.core.Messages.GitRan;
import dev.vexelray.demo.editor.core.Messages.GitRun;
import dev.vexelray.demo.editor.core.Messages.ShowDiff;
import dev.vexelray.demo.editor.core.Messages.Stage;
import dev.vexelray.demo.editor.core.Messages.Status;
import dev.vexelray.demo.editor.core.Messages.GitHead;
import dev.vexelray.demo.editor.core.Messages.GitHistory;
import dev.vexelray.demo.editor.core.Messages.HistoryAsk;
import dev.vexelray.demo.editor.core.Messages.HistoryRead;
import dev.vexelray.demo.editor.core.Messages.GitRead;
import dev.vexelray.demo.editor.core.Messages.GitRefresh;
import dev.vexelray.demo.editor.core.Messages.GitStamp;
import dev.vexelray.demo.editor.core.Messages.GitStampAsk;
import dev.vexelray.demo.editor.core.Messages.GitTick;
import dev.vexelray.demo.editor.core.Messages.GitTree;
import dev.vexelray.demo.editor.core.Messages.GitWatch;
import dev.vexelray.demo.editor.core.Messages.Plain;
import dev.vexelray.demo.editor.core.Messages.Relist;
import dev.vexelray.demo.editor.core.Messages.ShowFolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The git controller, alone: no window, no lanes, no git. Each test hands the {@link Repository} a message the way its
 * lane would and reads what it sent; the jobs' answers are handed in by hand, so an answer that comes late, or about a
 * root since left, is as easy to test as one that comes at once.
 */
class RepositoryTest {

    private static final Path ROOT = Path.of("C:/work/project").toAbsolutePath();
    private static final Path OTHER = Path.of("C:/work/other").toAbsolutePath();
    private static final Path GIT_DIR = ROOT.resolve(".git");

    private Repository repository;
    private final List<Object> sent = new ArrayList<>();

    @BeforeEach
    void build() {
        Atchung bus = Atchung.create();
        repository = new Repository(bus);
        bus.subscribe(Messages.GIT_STATUS_TOPIC, sent::add);
        bus.subscribe(Messages.GIT_STAMP_TOPIC, sent::add);
        bus.subscribe(Messages.GIT_HEAD_TOPIC, sent::add);
        bus.subscribe(Messages.GIT_TREE_TOPIC, sent::add);
        bus.subscribe(Messages.GIT_DIFF_TOPIC, sent::add);
        bus.subscribe(Messages.GIT_DIFF_SHOWN_TOPIC, sent::add);
        bus.subscribe(Messages.GIT_RUN_TOPIC, sent::add);
        bus.subscribe(Messages.STATUS_TOPIC, sent::add);
        bus.subscribe(Messages.GIT_COMMITTED_TOPIC, sent::add);
        bus.subscribe(Messages.GIT_HISTORY_TOPIC, sent::add);
        bus.subscribe(Messages.GIT_HISTORY_SHOWN_TOPIC, sent::add);
        bus.subscribe(Messages.GIT_PUSHED_TOPIC, sent::add);
        bus.subscribe(Messages.RELOAD_TOPIC, sent::add);
    }

    @Test
    void inGitModeTheHistoryIsAskedWhenGitDidSomethingAndOnlyTheLatestShown() {
        long g = shown();
        assertEquals(List.of(), took(HistoryAsk.class), "not in Git mode, so not asked");
        repository.watch(new GitWatch(true));
        repository.read(main(took(GitAsk.class).getFirst().generation(), 3));
        HistoryAsk first = took(HistoryAsk.class).getFirst();
        assertEquals(ROOT, first.root());

        repository.changed(new Relist(null));
        repository.read(main(took(GitAsk.class).getFirst().generation(), 3));
        assertEquals(List.of(), took(HistoryAsk.class), "a save moves no stamp, and asks nothing");

        repository.changed(new Relist(null));
        repository.read(answer(g, "main", 3, 0, 2, "stamp-2"));
        HistoryAsk second = took(HistoryAsk.class).getFirst();

        repository.refresh(new GitRefresh());
        repository.read(answer(g, "main", 3, 0, 2, "stamp-2"));
        HistoryAsk third = took(HistoryAsk.class).getFirst();
        assertTrue(third.ticket() > second.ticket(), "Refresh asks whatever the stamp says");

        repository.historyRead(new HistoryRead(second.ticket(), "old", "old"));
        assertEquals(List.of(), took(GitHistory.class), "an answer overtaken is dropped");
        repository.historyRead(new HistoryRead(third.ticket(), "log", "branches"));
        assertEquals(List.of(new GitHistory(ROOT, "log", "branches")), took(GitHistory.class));

        repository.folder(new ShowFolder(OTHER));
        repository.historyRead(new HistoryRead(third.ticket(), "log", "branches"));
        assertEquals(List.of(), took(GitHistory.class), "about a root since left");
    }

    private <T> List<T> took(Class<T> kind) {
        List<T> out = sent.stream().filter(kind::isInstance).map(kind::cast).toList();
        sent.removeIf(kind::isInstance);
        return out;
    }

    private static GitRead main(long generation, int changed) {
        return answer(generation, "main", 2, 0, changed, "stamp-1");
    }

    private static GitRead answer(long generation, String branch, int ahead, int behind, int changed, String stamp) {
        return new GitRead(generation, true, branch, "origin/" + branch, ahead, behind, 1, changed, changed, ROOT,
                GIT_DIR, stamp, "status-text", "unstaged-text", "staged-text");
    }

    private static GitRead none(long generation) {
        return new GitRead(generation, false, "", "", 0, 0, 0, 0, 0, null, null, "", "", "", "");
    }

    /** Show the root and answer its first question; the answer's generation. */
    private long shown() {
        repository.folder(new ShowFolder(ROOT));
        long g = took(GitAsk.class).getFirst().generation();
        repository.read(main(g, 3));
        sent.clear();
        return g;
    }

    @Test
    void aRootIsAskedAboutAndItsAnswerToldToTheView() {
        repository.folder(new ShowFolder(ROOT));
        List<GitAsk> asked = took(GitAsk.class);
        assertEquals(1, asked.size());
        assertEquals(ROOT, asked.getFirst().folder());

        repository.read(main(asked.getFirst().generation(), 3));
        assertEquals(List.of(new GitHead(true, "main ↑2 · 3 changed", "main ↑2", "main", "origin/main", 2, 0, 1, 3)),
                took(GitHead.class));
        assertEquals(List.of(new GitTree(ROOT, "status-text", "unstaged-text", "staged-text")), took(GitTree.class));
    }

    @Test
    void aBurstOfSavesIsOneMoreQuestionNotOneEach() {
        repository.folder(new ShowFolder(ROOT));
        long g = took(GitAsk.class).getFirst().generation();

        for (int i = 0; i < 20; i++) {
            repository.changed(new Relist(null));
        }
        assertEquals(List.of(), took(GitAsk.class), "one is out already");

        repository.read(main(g, 0));
        assertEquals(1, took(GitAsk.class).size(), "asked once more, for all twenty");
        repository.read(main(g, 1));
        repository.changed(new Relist(null));
        assertEquals(1, took(GitAsk.class).size(), "nothing out, so asked at once");
    }

    @Test
    void anAnswerAboutARootSinceLeftIsDropped() {
        repository.folder(new ShowFolder(ROOT));
        long old = took(GitAsk.class).getFirst().generation();
        repository.folder(new ShowFolder(OTHER));
        GitAsk now = took(GitAsk.class).getFirst();
        assertEquals(OTHER, now.folder());

        repository.read(main(old, 5));
        assertEquals(List.of(), took(GitHead.class));
        assertEquals(List.of(), took(GitTree.class));
        repository.read(answer(now.generation(), "develop", 0, 1, 0, "s"));
        assertEquals("develop ↓1", took(GitHead.class).getFirst().label());
    }

    @Test
    void outsideARepositoryTheStatusLineSaysNothingAndThereIsNoTree() {
        repository.folder(new ShowFolder(ROOT));
        long g = took(GitAsk.class).getFirst().generation();
        repository.read(none(g));
        assertEquals(List.of(Messages.NOT_A_REPOSITORY), took(GitHead.class));
        assertEquals(List.of(), took(GitTree.class));
    }

    @Test
    void withNoRootNothingIsAskedAndTheSlotIsCleared() {
        repository.changed(new Relist(null));
        assertEquals(List.of(), took(GitAsk.class));

        repository.folder(new ShowFolder(ROOT));
        long g = took(GitAsk.class).getFirst().generation();
        repository.plain(new Plain());
        assertEquals(List.of(Messages.NOT_A_REPOSITORY), took(GitHead.class));
        repository.read(main(g, 2));
        assertEquals(List.of(), took(GitHead.class), "the answer about the root that went is dropped");
        repository.changed(new Relist(null));
        assertEquals(List.of(), took(GitAsk.class));
    }

    @Test
    void enteringGitModeAndRefreshAskAtOnce() {
        shown();
        repository.watch(new GitWatch(true));
        assertEquals(1, took(GitAsk.class).size());
        repository.refresh(new GitRefresh());
        assertEquals(List.of(), took(GitAsk.class), "one is out; the refresh waits for it");
        repository.watch(new GitWatch(false));
        assertEquals(List.of(), took(GitAsk.class), "leaving asks nothing");
    }

    @Test
    void whileWatchingATickLooksAtTheStampAndAsksOnlyWhenItMoved() {
        long g = shown();
        repository.tick(new GitTick());
        assertEquals(List.of(), took(GitStampAsk.class), "not watching yet");

        repository.watch(new GitWatch(true));
        repository.read(main(took(GitAsk.class).getFirst().generation(), 3));
        sent.clear();

        repository.tick(new GitTick());
        assertEquals(List.of(new GitStampAsk(g, GIT_DIR)), took(GitStampAsk.class));
        repository.tick(new GitTick());
        assertEquals(List.of(), took(GitStampAsk.class), "one look at a time");

        repository.stamped(new GitStamp(g, "stamp-1"));
        assertEquals(List.of(), took(GitAsk.class), "nothing moved, so git is not asked");

        repository.tick(new GitTick());
        took(GitStampAsk.class);
        repository.stamped(new GitStamp(g, "stamp-2"));
        assertEquals(1, took(GitAsk.class).size(), "a commit at a prompt moved the stamp");
        repository.tick(new GitTick());
        assertEquals(List.of(), took(GitStampAsk.class), "no look while the question is out");
    }

    @Test
    void aStampAboutARootSinceLeftIsDropped() {
        long g = shown();
        repository.watch(new GitWatch(true));
        took(GitAsk.class);
        repository.folder(new ShowFolder(OTHER));
        took(GitAsk.class);
        repository.stamped(new GitStamp(g, "moved"));
        assertEquals(List.of(), took(GitAsk.class));
    }

    @Test
    void aPickedRowsDiffIsAskedForAndOnlyTheLatestShown() {
        shown();
        repository.showDiff(new ShowDiff("src/Ui.java", Diff.Side.UNSTAGED));
        DiffAsk first = took(DiffAsk.class).getFirst();
        assertEquals(new DiffAsk(first.ticket(), ROOT, "src/Ui.java", Diff.Side.UNSTAGED), first);

        repository.showDiff(new ShowDiff("pom.xml", Diff.Side.STAGED));
        DiffAsk second = took(DiffAsk.class).getFirst();
        repository.diffRead(new DiffRead(first.ticket(), "src/Ui.java", Diff.Side.UNSTAGED, "old"));
        assertEquals(List.of(), took(GitDiff.class), "picked past");
        repository.diffRead(new DiffRead(second.ticket(), "pom.xml", Diff.Side.STAGED, "@@ -1 +1 @@\n"));
        assertEquals(List.of(new GitDiff(ROOT, "pom.xml", Diff.Side.STAGED, "@@ -1 +1 @@\n")), took(GitDiff.class));
    }

    @Test
    void aNewStatusAsksForTheDiffOnShowAgainAndAClosedOneIsNot() {
        long g = shown();
        repository.showDiff(new ShowDiff("src/Ui.java", Diff.Side.UNSTAGED));
        took(DiffAsk.class);
        repository.changed(new Relist(null));
        repository.read(main(took(GitAsk.class).getFirst().generation(), 2));
        assertEquals(1, took(DiffAsk.class).size(), "the save may have changed it");

        repository.showDiff(new ShowDiff(null, Diff.Side.UNSTAGED));
        assertEquals(List.of(), took(DiffAsk.class));
        repository.changed(new Relist(null));
        repository.read(main(took(GitAsk.class).getFirst().generation(), 2));
        assertEquals(List.of(), took(DiffAsk.class));
    }

    @Test
    void aDiffAboutARootSinceLeftIsDropped() {
        shown();
        repository.showDiff(new ShowDiff("src/Ui.java", Diff.Side.UNSTAGED));
        DiffAsk asked = took(DiffAsk.class).getFirst();
        repository.folder(new ShowFolder(OTHER));
        repository.diffRead(new DiffRead(asked.ticket(), "src/Ui.java", Diff.Side.UNSTAGED, "x"));
        assertEquals(List.of(), took(GitDiff.class));
    }

    @Test
    void writesGoOneAtATimeAndEachIsFollowedByAFreshStatus() {
        long g = shown();
        repository.stage(new Stage("src/Ui.java", true));
        repository.stage(new Stage("src/Old.java", false));
        repository.stage(new Stage(null, true));
        assertEquals(List.of(new GitRun(g, ROOT, GitOp.STAGE, "src/Ui.java")), took(GitRun.class),
                "one is out; the rest wait, so they never meet at index.lock");

        repository.ran(new GitRan(g, GitOp.STAGE, "src/Ui.java", true, "git add -A -- src/Ui.java", ""));
        assertEquals(1, took(GitAsk.class).size());
        assertEquals(List.of(new GitRun(g, ROOT, GitOp.UNSTAGE, "src/Old.java")), took(GitRun.class));

        repository.ran(new GitRan(g, GitOp.UNSTAGE, "src/Old.java", true, "git restore", ""));
        assertEquals(List.of(new GitRun(g, ROOT, GitOp.STAGE, ".")), took(GitRun.class),
                "everything is everything under the root");
        repository.ran(new GitRan(g, GitOp.STAGE, ".", true, "git add -A -- .", ""));
        assertEquals(List.of(), took(GitRun.class));
    }

    @Test
    void aWriteThatFailsSaysSoInGitsWords() {
        long g = shown();
        repository.stage(new Stage("src/Ui.java", true));
        took(GitRun.class);
        repository.ran(new GitRan(g, GitOp.STAGE, "src/Ui.java", false, "git add",
                "Unable to create 'C:/work/project/.git/index.lock': File exists."));
        Status said = took(Status.class).getFirst();
        assertTrue(said.alert());
        assertEquals("Could not stage src/Ui.java: Unable to create 'C:/work/project/.git/index.lock': File exists.",
                said.message());
        assertEquals(1, took(GitAsk.class).size(), "still asked again: some of it may have happened");
    }

    @Test
    void writesForARootSinceLeftAreNotMadeInTheNewOne() {
        long g = shown();
        repository.stage(new Stage("a", true));
        repository.stage(new Stage("b", true));
        took(GitRun.class);
        repository.folder(new ShowFolder(OTHER));
        repository.ran(new GitRan(g, GitOp.STAGE, "a", true, "", ""));
        assertEquals(List.of(), took(GitRun.class));
    }

    @Test
    void aCommitWaitsBehindTheStagesAskedBeforeItAndSaysHowItWent() {
        long g = shown();
        repository.stage(new Stage("src/Ui.java", true));
        repository.commit(new CommitStaged("\n  Change the UI  \n\nWhy.\n"));
        took(GitRun.class);
        repository.ran(new GitRan(g, GitOp.STAGE, "src/Ui.java", true, "git add", ""));
        assertEquals(List.of(new GitRun(g, ROOT, GitOp.COMMIT, null, "\n  Change the UI  \n\nWhy.\n")),
                took(GitRun.class), "the message goes as it was typed; git cleans it up");
        repository.ran(new GitRan(g, GitOp.COMMIT, null, true, "git commit -F -", ""));
        assertEquals(List.of(new GitCommitted(true, "Change the UI")), took(GitCommitted.class));
        assertEquals(new Status("Committed: Change the UI", false), took(Status.class).getFirst());
        assertEquals(1, took(GitAsk.class).size());
    }

    @Test
    void aCommitThatFailsGivesTheMessageBackAndSaysWhy() {
        long g = shown();
        repository.commit(new CommitStaged("Change"));
        took(GitRun.class);
        repository.ran(new GitRan(g, GitOp.COMMIT, null, false, "git commit -F -", "lint: a.txt is wrong"));
        assertEquals(List.of(new GitCommitted(false, "Change")), took(GitCommitted.class));
        assertEquals(new Status("Could not commit: lint: a.txt is wrong", true), took(Status.class).getFirst());
    }

    @Test
    void aCommitThatCannotGoIsRefusedAtOnce() {
        repository.folder(new ShowFolder(ROOT));
        repository.commit(new CommitStaged("Change"));
        assertEquals("Not in a repository", took(Status.class).getFirst().message());
        long g = took(GitAsk.class).getFirst().generation();
        repository.read(answer(g, "main", 0, 0, 1, "s"));
        repository.commit(new CommitStaged(" \n \n"));
        assertEquals("A commit needs a message", took(Status.class).getFirst().message());
        repository.commit(new CommitStaged("One"));
        repository.commit(new CommitStaged("Two"));
        assertEquals("A commit is already being made", took(Status.class).getFirst().message());
        assertEquals(1, took(GitRun.class).size());
        assertEquals(List.of(new GitCommitted(false, ""), new GitCommitted(false, ""), new GitCommitted(false, "")),
                took(GitCommitted.class));
        assertTrue(!Repository.committable(0, "One"), "nothing staged");
    }

    @Test
    void aCommitOutWhenTheRootChangesGivesTheBoxBack() {
        shown();
        repository.commit(new CommitStaged("Change"));
        took(GitCommitted.class);
        repository.folder(new ShowFolder(OTHER));
        assertEquals(List.of(new GitCommitted(false, "")), took(GitCommitted.class));
    }

    @Test
    void aSubjectIsTheFirstLineThatSaysSomething() {
        assertEquals("Fix it", Repository.subject("\n\n  Fix it \r\nmore"));
        assertEquals("", Repository.subject(""));
        assertEquals("", Repository.subject(null));
        assertTrue(Repository.committable(1, "x"));
    }

    @Test
    void beforeTheFirstStatusThereIsNothingToStage() {
        repository.folder(new ShowFolder(ROOT));
        repository.stage(new Stage("a", true));
        assertEquals(List.of(), took(GitRun.class));
    }

    @Test
    void aPushWaitsBehindTheCommitBeforeItAndSaysHowItWent() {
        long g = shown();
        repository.commit(new CommitStaged("Change"));
        repository.push(new PushBranch());
        assertEquals(new Status("Pushing main to origin/main…", false), took(Status.class).getFirst());
        assertEquals(List.of(GitOp.COMMIT), took(GitRun.class).stream().map(GitRun::op).toList());
        repository.ran(new GitRan(g, GitOp.COMMIT, null, true, "git commit -F -", ""));
        assertEquals(List.of(new GitRun(g, ROOT, GitOp.PUSH, null)), took(GitRun.class));
        repository.read(main(g, 0));   // the status asked after the commit
        sent.clear();
        repository.ran(new GitRan(g, GitOp.PUSH, null, true, "git push", ""));
        assertEquals(List.of(new GitPushed(true)), took(GitPushed.class));
        assertEquals(new Status("Pushed main to origin/main", false), took(Status.class).getFirst());
        assertEquals(1, took(GitAsk.class).size(), "the branch is asked about again, no longer ahead");
    }

    @Test
    void aPushThatFailsSaysWhyAndPushCanBePressedAgain() {
        long g = shown();
        repository.push(new PushBranch());
        sent.clear();
        repository.ran(new GitRan(g, GitOp.PUSH, null, false, "git push", "rejected main -> main (fetch first)"));
        assertEquals(List.of(new GitPushed(false)), took(GitPushed.class));
        assertEquals(new Status("Could not push: rejected main -> main (fetch first)", true),
                took(Status.class).getFirst());
        repository.push(new PushBranch());
        assertEquals(1, took(GitRun.class).size());
    }

    @Test
    void aPushThatCannotGoIsRefusedAtOnce() {
        repository.folder(new ShowFolder(ROOT));
        repository.push(new PushBranch());
        assertEquals("Not in a repository", took(Status.class).getFirst().message());
        long g = took(GitAsk.class).getFirst().generation();
        repository.read(answer(g, "main", 0, 0, 0, "s"));
        repository.push(new PushBranch());
        assertEquals("Nothing to push: main is where origin/main is", took(Status.class).getFirst().message());
        repository.read(new GitRead(g, true, "spike", "", 0, 0, 0, 0, 0, ROOT, GIT_DIR, "s", "", "", ""));
        repository.push(new PushBranch());
        assertEquals("spike has no upstream to push to", took(Status.class).getFirst().message());
        repository.read(answer(g, "main", 1, 0, 0, "s"));
        repository.push(new PushBranch());
        repository.push(new PushBranch());
        assertEquals("A push is already going", took(Status.class).getLast().message());
        assertEquals(1, took(GitRun.class).size());
        assertEquals(List.of(new GitPushed(false), new GitPushed(false), new GitPushed(false), new GitPushed(false)),
                took(GitPushed.class));
        assertTrue(Repository.pushable("origin/main", 1));
        assertTrue(!Repository.pushable("", 3), "nowhere to push to");
    }

    @Test
    void aPushOutWhenTheRootChangesLetsPushGo() {
        shown();
        repository.push(new PushBranch());
        took(GitPushed.class);
        repository.folder(new ShowFolder(OTHER));
        assertEquals(List.of(new GitPushed(false)), took(GitPushed.class));
    }

    @Test
    void aSwitchIsAWriteAndTheTabsUnderTheRepositoryAreReadAgain() {
        long g = shown();
        repository.switchTo(new SwitchBranch("main"));
        assertEquals(new Status("Already on main", false), took(Status.class).getFirst());
        assertEquals(List.of(), took(GitRun.class));

        repository.switchTo(new SwitchBranch("feature"));
        assertEquals(List.of(new GitRun(g, ROOT, GitOp.SWITCH, "feature")), took(GitRun.class));
        repository.ran(new GitRan(g, GitOp.SWITCH, "feature", true, "git switch feature", ""));
        assertEquals(new Status("Switched to feature", false), took(Status.class).getFirst());
        assertEquals(List.of(new Reload(ROOT)), took(Reload.class));
        assertEquals(1, took(GitAsk.class).size());

        repository.switchTo(new SwitchBranch("spike"));
        took(GitRun.class);
        repository.ran(new GitRan(g, GitOp.SWITCH, "spike", false, "git switch spike",
                "Your local changes to the following files would be overwritten by checkout:"));
        assertEquals(new Status("Could not switch to spike: Your local changes to the following files would be "
                + "overwritten by checkout:", true), took(Status.class).getFirst());
        assertEquals(List.of(), took(Reload.class), "nothing was rewritten");
    }

    @Test
    void aPushFailureIsSaidAsTheRemoteSaidIt() {
        assertEquals("rejected main -> main (fetch first)", RunGit.pushLine(
                "To C:/work/origin.git\n ! [rejected]        main -> main (fetch first)\n"
                        + "error: failed to push some refs to 'C:/work/origin.git'\nhint: Updates were rejected\n"));
        assertEquals("remote rejected main -> main (pre-receive hook declined)", RunGit.pushLine(
                "To host:x.git\n ! [remote rejected] main -> main (pre-receive hook declined)\n"));
        assertEquals("Authentication failed for 'https://github.com/sibarum/vex.git/'", RunGit.pushLine(
                "fatal: Authentication failed for 'https://github.com/sibarum/vex.git/'\n"));
        assertEquals("git@github.com: Permission denied (publickey).", RunGit.pushLine(
                "git@github.com: Permission denied (publickey).\nfatal: Could not read from remote repository.\n"));
        assertEquals("git failed", RunGit.pushLine(""));
    }

    @Test
    void aCleanBranchInStepWithItsUpstreamIsJustItsName() {
        GitHead h = Repository.head(answer(1, "main", 0, 0, 0, ""));
        assertEquals("main", h.label());
        assertEquals("main", h.chip());
    }
}
