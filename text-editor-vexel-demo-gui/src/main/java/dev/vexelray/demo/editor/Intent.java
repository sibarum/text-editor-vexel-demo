package dev.vexelray.demo.editor;

import sibarum.atchung.Topic;

/**
 * Something the person did that the {@link View} has to act on, sent from the thread it happened on to the view's own
 * lane.
 *
 * <p>A key chord, a menu item and a dialog's answer all arrive on a worker, and the tabs are the view's to read: so the
 * worker only says what happened, and the view does it, in order, on its own thread. {@code id} is the tab a gesture
 * is about, or the ticket a {@link Gates} or the view keeps something under for {@link Command#QUIT},
 * {@link Command#SETTLE}, {@link Command#ROOT} and {@link Command#RUN}; {@code NO_TAB} where neither applies.
 */
record Intent(Command command, long id) {

    static final String TOPIC = "view.intent";
    static final Topic<Intent> INTENTS = Topic.of(TOPIC, Intent.class);

    /** Each command is a method on the view, so there is no switch to keep in step with this list. */
    enum Command {
        SAVE_FRONT {
            @Override
            void on(View view, long id) {
                view.saveFront();
            }
        },
        SAVE_AS_FRONT {
            @Override
            void on(View view, long id) {
                view.saveAsFront();
            }
        },
        CLOSE_FRONT {
            @Override
            void on(View view, long id) {
                view.closeFront();
            }
        },
        NEXT_TAB {
            @Override
            void on(View view, long id) {
                view.cycle(1);
            }
        },
        PREVIOUS_TAB {
            @Override
            void on(View view, long id) {
                view.cycle(-1);
            }
        },
        CLOSE {
            @Override
            void on(View view, long id) {
                view.close(id);
            }
        },
        CLOSE_OTHERS {
            @Override
            void on(View view, long id) {
                view.closeOthers(id);
            }
        },
        CLOSE_ALL {
            @Override
            void on(View view, long id) {
                view.closeAll();
            }
        },
        /** Ctrl+Enter: go to where the name under the caret is declared. */
        DECLARATION {
            @Override
            void on(View view, long id) {
                view.goToDeclaration();
            }
        },
        /** The window was asked to close; {@code id} is the close gate's ticket. */
        QUIT {
            @Override
            void on(View view, long id) {
                view.quit(id);
            }
        },
        /** About to go where a root is picked; {@code id} is the gate's ticket. */
        SETTLE {
            @Override
            void on(View view, long id) {
                view.settle(id);
            }
        },
        /** A new root was picked; {@code id} is the view's ticket for the folder. */
        ROOT {
            @Override
            void on(View view, long id) {
                view.root(id);
            }
        },
        /** Something was chosen from the Run menu; {@code id} is the view's ticket for it. */
        RUN {
            @Override
            void on(View view, long id) {
                view.run(id);
            }
        };

        abstract void on(View view, long id);
    }
}
