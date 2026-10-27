package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Messages;
import dev.vexelray.framework.api.Publishes;
import sibarum.atchung.Atchung;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The questions the framework and the navigator ask with two callbacks, turned into messages: the main window's close
 * gate, and the navigator's "may I go to Open" before a new root is picked.
 *
 * <p>Each asks with {@code proceed} and {@code cancel}, and a callback cannot be sent to another lane: it would carry
 * whatever it closes over. So the gate keeps them here, under a ticket, and sends the ticket instead: to the
 * {@link View}, which tells the editor which tabs are open and unsaved, and the editor's {@link Messages.Settled}
 * comes back with the ticket and is answered here. Exactly one of the two runs, once.
 *
 * <p>Shared between the worker that asks and the view's lane, which answers, so its one map is concurrent. It holds
 * nothing else.
 */
@Publishes(Intent.TOPIC)
final class Gates {

    private record Pending(Runnable proceed, Runnable cancel) {
    }

    private final Atchung bus;
    private final AtomicLong tickets = new AtomicLong();
    private final Map<Long, Pending> waiting = new ConcurrentHashMap<>();

    Gates(Atchung bus) {
        this.bus = bus;
    }

    /** The framework's {@code CloseGate}: the window has been asked to close. */
    void quit(Runnable proceed, Runnable cancel) {
        bus.publish(Intent.INTENTS, new Intent(Intent.Command.QUIT, hold(proceed, cancel)));
    }

    /** The navigator's gate before Open, and the folder dialog's: unsaved work is settled first, or the way is not taken. */
    void settle(Runnable proceed, Runnable cancel) {
        bus.publish(Intent.INTENTS, new Intent(Intent.Command.SETTLE, hold(proceed, cancel)));
    }

    private long hold(Runnable proceed, Runnable cancel) {
        long ticket = tickets.incrementAndGet();
        waiting.put(ticket, new Pending(proceed, cancel));
        return ticket;
    }

    /** What the editor decided for {@code ticket}. A ticket already answered, or never issued, is ignored. */
    void answer(long ticket, boolean proceed) {
        Pending p = waiting.remove(ticket);
        if (p != null) {
            (proceed ? p.proceed() : p.cancel()).run();
        }
    }
}
