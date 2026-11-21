package dev.vexelray.demo.editor.core;

import dev.vexelray.framework.highlight.Language;
import dev.vexelray.framework.highlight.Lexer;
import sibarum.pontif.tooling.PontifTokens;

import java.util.EnumMap;
import java.util.Map;

/**
 * Pontif, natively: {@code .ptf} as a language the framework's highlighting colours, by the language's own token scan
 * rather than a TextMate grammar ({@link Core#pontif} provides it).
 *
 * <p>The scan is pontif-tooling's tolerant {@link PontifTokens}, whose keywords are the parser's, so a keyword the
 * language gains is coloured with nothing here to change. Each token is given a TextMate-style scope
 * ({@link #SCOPES}) and then coloured by the same table as every other language, so Pontif wears the editor's palette
 * and not one of its own. The scan keeps no state, so it is safe for two documents at once, and the highlighting runs it
 * outside the lock its grammars share, so a Pontif document never waits behind a TextMate one.
 */
final class Pontif {

    /** The scope of a Pontif source file. Not a TextMate grammar's: see the class comment. */
    static final String SCOPE = "source.pontif";

    /**
     * Each kind of Pontif token as the TextMate scope the colour table knows it by. A name is a type when it begins
     * with a capital letter, the language's convention for sorts, structs and traits ({@code Int}, {@code Point}):
     * a lexical guess, as a TextMate grammar's is, until a checked document can say.
     */
    private static final Map<PontifTokens.Kind, String> SCOPES = new EnumMap<>(Map.of(
            PontifTokens.Kind.COMMENT, "comment.line.number-sign.pontif",
            PontifTokens.Kind.STRING, "string.quoted.double.pontif",
            PontifTokens.Kind.CHAR, "string.quoted.single.pontif",
            PontifTokens.Kind.NUMBER, "constant.numeric.pontif",
            PontifTokens.Kind.SUBJECT, "variable.language.subject.pontif",
            PontifTokens.Kind.KEYWORD, "keyword.other.pontif",
            PontifTokens.Kind.NAME, "variable.other.pontif"));
    private static final String TYPE = "entity.name.type.pontif";
    private static final String LITERAL = "constant.language.pontif";

    /** Every Pontif token, with its scope. The scan promises never to throw; if it does, the file is plain text. */
    private static final Lexer LEXER = (text, tokens) -> {
        for (PontifTokens.Token token : PontifTokens.scan(text)) {
            tokens.token(token.start(), token.end(), scope(token, text));
        }
    };

    /** The language: {@code .ptf}, called "Pontif", coloured by {@link #LEXER}. */
    static final Language LANGUAGE = Language.lexed(SCOPE, "Pontif", LEXER).extensions("ptf");

    private Pontif() {
    }

    /** The scope of one token: its kind's, but for a capitalised name (a type) and {@code true}/{@code false}. */
    static String scope(PontifTokens.Token token, String text) {
        String word = text.substring(token.start(), token.end());
        if (token.kind() == PontifTokens.Kind.NAME && !word.isEmpty() && Character.isUpperCase(word.charAt(0))) {
            return TYPE;
        }
        if (token.kind() == PontifTokens.Kind.KEYWORD && (word.equals("true") || word.equals("false"))) {
            return LITERAL;
        }
        return SCOPES.get(token.kind());
    }
}
