package dev.vexelray.demo.editor.core;

import dev.vexelray.gui.core.app.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The preferences: the editor's own, a root's own in {@code .vex}, and {@code .vex} kept out of git. */
class PreferencesTest {

    @TempDir
    Path home;

    @TempDir
    Path work;

    private Preferences prefs() {
        return new Preferences(Settings.at(home.resolve("settings.properties")));
    }

    @Test
    void theDefaultsAreDarkWrappedAndIndexed() {
        Preferences p = prefs();
        assertEquals(Preferences.Shade.DARK, p.theme());
        assertTrue(p.wrap());
        assertTrue(p.index());
        assertTrue(p.gitignore());
    }

    @Test
    void theThemeIsReadBackFromTheSettingsAtLaunch() {
        prefs().theme(Preferences.Shade.LIGHT);
        Settings reread = Settings.at(home.resolve("settings.properties"));
        assertEquals(Preferences.Shade.LIGHT, Preferences.theme(reread));
    }

    @Test
    void withoutVexARootUsesTheEditorsOwn() throws IOException {
        Path project = Files.createDirectories(work.resolve("p"));
        Preferences p = prefs();
        p.root(project);
        p.wrap(false);
        assertFalse(p.projectScoped());
        assertFalse(Files.exists(project.resolve(Preferences.DIR)));
        assertFalse(prefs().wrap(), "the editor's own settings were written");
    }

    @Test
    void aRootKeepingItsOwnOverridesTheEditorsAndOnlyThere() throws IOException {
        Path a = Files.createDirectories(work.resolve("a"));
        Path b = Files.createDirectories(work.resolve("b"));
        Preferences p = prefs();
        p.root(a);
        assertTrue(p.keepForProject(true));
        assertTrue(Files.isRegularFile(a.resolve(Preferences.DIR).resolve(Preferences.FILE)));
        p.wrap(false);
        p.index(false);

        p.root(b);
        assertTrue(p.wrap(), "another root keeps the editor's own");
        assertTrue(p.index());

        Preferences fresh = prefs();
        fresh.root(a);
        assertTrue(fresh.projectScoped(), "found again on the next start");
        assertFalse(fresh.wrap());
        assertFalse(fresh.index());
    }

    @Test
    void keepingStartsFromTheValuesInForce() throws IOException {
        Path a = Files.createDirectories(work.resolve("a"));
        Preferences p = prefs();
        p.wrap(false);
        p.root(a);
        p.keepForProject(true);
        assertFalse(p.wrap());
        p.wrap(true);
        p.root(null);
        assertFalse(p.wrap(), "the project's change stayed in the project");
    }

    @Test
    void lettingGoTakesTheFileAndTheEmptyFolderAway() throws IOException {
        Path a = Files.createDirectories(work.resolve("a"));
        Preferences p = prefs();
        p.root(a);
        p.keepForProject(true);
        p.wrap(false);
        assertTrue(p.keepForProject(false));
        assertFalse(p.projectScoped());
        assertFalse(Files.exists(a.resolve(Preferences.DIR)));
        assertTrue(p.wrap(), "the editor's own again");
    }

    @Test
    void lettingGoLeavesAnythingElseInVex() throws IOException {
        Path a = Files.createDirectories(work.resolve("a"));
        Preferences p = prefs();
        p.root(a);
        p.keepForProject(true);
        Files.writeString(a.resolve(Preferences.DIR).resolve("notes.txt"), "mine");
        p.keepForProject(false);
        assertTrue(Files.isRegularFile(a.resolve(Preferences.DIR).resolve("notes.txt")));
        assertFalse(Files.exists(a.resolve(Preferences.DIR).resolve(Preferences.FILE)));
    }

    @Test
    void keepingInAGitTreeIgnoresVexOnce() throws IOException {
        Path repo = Files.createDirectories(work.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".gitignore"), "target/");
        Preferences p = prefs();
        p.root(repo);
        p.keepForProject(true);
        p.keepForProject(false);
        p.keepForProject(true);
        assertEquals("target/\n.vex/\n", Files.readString(repo.resolve(".gitignore")));
    }

    @Test
    void aModuleOfARepositoryGetsAGitignoreOfItsOwn() throws IOException {
        Path repo = Files.createDirectories(work.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        Path module = Files.createDirectories(repo.resolve("module"));
        Preferences p = prefs();
        p.root(module);
        p.keepForProject(true);
        assertEquals(".vex/\n", Files.readString(module.resolve(".gitignore")));
    }

    @Test
    void theGitignoreKeepsItsLineEndingsAndAnExistingRule() throws IOException {
        Path repo = Files.createDirectories(work.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".gitignore"), "target/\r\nout/\r\n");
        Preferences.ignore(repo);
        assertEquals("target/\r\nout/\r\n.vex/\r\n", Files.readString(repo.resolve(".gitignore")));

        Files.writeString(repo.resolve(".gitignore"), "/.vex\n");
        Preferences.ignore(repo);
        assertEquals("/.vex\n", Files.readString(repo.resolve(".gitignore")), "already ignored");
    }

    @Test
    void outsideGitOrWithTheOptionOffNoGitignoreIsMade() throws IOException {
        Path plain = Files.createDirectories(work.resolve("plain"));
        Preferences p = prefs();
        p.root(plain);
        p.keepForProject(true);
        assertFalse(Files.exists(plain.resolve(".gitignore")));

        Path repo = Files.createDirectories(work.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        p.gitignore(false);
        p.root(repo);
        p.keepForProject(true);
        assertFalse(Files.exists(repo.resolve(".gitignore")));
        p.gitignore(true);
        assertTrue(Files.exists(repo.resolve(".gitignore")), "turned on with the project already keeping its own");
    }
}
