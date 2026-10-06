package dev.vexelray.demo.editor;

import dev.vexelray.framework.api.VexelApp;

/**
 * The release edition's application declaration: {@link TextEditor}'s facts and no starters. In particular no
 * {@code AutomationStarter}, and the pom drops {@code vexelray-framework-automation} from the classpath under
 * {@code -Pnative-release}, so a shipped binary cannot open a driving socket ({@code --automation} parses and does
 * nothing). The debug edition is {@code src/edition-debug}; keep the two annotations identical apart from
 * {@code starters}.
 */
@VexelApp(name = TextEditor.APP, title = TextEditor.TITLE, width = TextEditor.W, height = TextEditor.H)
final class TextEditorApp {

    private TextEditorApp() {
    }
}
