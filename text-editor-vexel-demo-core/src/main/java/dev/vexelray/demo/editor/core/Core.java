package dev.vexelray.demo.editor.core;

import dev.vexelray.framework.api.Configuration;

/**
 * What this module is made of: the {@link Editor} and the jobs that touch the disk.
 *
 * <p>The annotation processor sees only the sources of the module it is compiling, so when {@code -gui} compiles,
 * everything here is a class file it would never construct. This list is how it hears of them: {@code -gui}'s
 * {@code TextEditorApp} names this class among its {@code starters}, and the generated wiring builds what is listed
 * here as if it were its own, and checks every message between them. A new component or job in this module goes on
 * this list, or nothing builds it.
 *
 * <p>The services the editor and the view both read ({@link Scratches}, {@link Preferences}, {@link ProjectIndex}) are
 * built by {@code -gui}'s {@code Recipes}: a recipe here returning one of this module's own classes would have to
 * return an interface instead, and to {@code -gui} they are somebody else's classes, which may be returned as they are.
 */
@Configuration(components = Editor.class,
        jobs = {ReadFile.class, WriteFile.class, FindPath.class, MakeScratch.class, NameScratch.class,
                DropScratch.class, Make.class})
public final class Core {
}
