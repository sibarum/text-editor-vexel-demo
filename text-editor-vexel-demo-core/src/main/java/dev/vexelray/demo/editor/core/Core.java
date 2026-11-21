package dev.vexelray.demo.editor.core;

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.highlight.Language;

/**
 * What this module is made of: the {@link Editor} and the jobs that touch the disk, and the one language it colours
 * that is not a bundled grammar.
 *
 * <p>The annotation processor sees only the sources of the module it is compiling, so when {@code -gui} compiles,
 * everything here is a class file it would never construct. This list is how it hears of them: {@code -gui}'s
 * {@code TextEditorApp} names this class among its {@code starters}, and the generated wiring builds what is listed
 * here as if it were its own, and checks every message between them. A new component or job in this module goes on
 * this list, or nothing builds it.
 */
@Configuration(components = Editor.class,
        jobs = {ReadFile.class, WriteFile.class, FindPath.class, CreateFile.class, ReadSession.class, ListFolder.class,
                MarkText.class, FormatText.class, WriteSession.class, SurveyFolder.class, MakeScratch.class,
                DropScratch.class, FindScratches.class, MoveScratch.class, MakeScratchFolder.class, FindRuns.class,
                TypeRun.class, CheckText.class, NavigateText.class, BuildIndex.class, FindSymbols.class, ReadGitStatus.class,
                FindProject.class, FindTools.class, KeepTools.class},
        sources = ScanRepos.class,
        destinations = Runner.class)
public final class Core {

    /**
     * Pontif, coloured by its own token scan ({@link Pontif}): one more language for the framework's
     * {@code HighlightStarter}, which takes every {@code Language} the application provides. Here, beside the scan,
     * because pontif-tooling is this module's dependency and not {@code -gui}'s.
     */
    @Provides
    public Language pontif() {
        return Pontif.LANGUAGE;
    }
}
