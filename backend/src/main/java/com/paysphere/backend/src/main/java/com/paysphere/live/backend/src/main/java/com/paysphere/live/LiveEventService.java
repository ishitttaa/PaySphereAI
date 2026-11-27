package com.paysphere.live;

import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LiveEventService {

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    /** Opens one browser connection. */
    public SseEmitter subscribe(long timeoutMillis) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> emitters.remove(emitter));
        send(emitter, SseEmitter.event().name("connected").data("ok"));
        return emitter;
    }

    /** Runs only after the database commit, on a separate thread. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLiveEvent(LiveEvent event) {
        emitters.forEach(emitter ->
                send(emitter, SseEmitter.event().name(event.type()).data(event)));
    }

    /** Keeps idle connections open and removes dead ones. */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        emitters.forEach(emitter -> send(emitter, SseEmitter.event().comment("ping")));
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder message) {
        try {
            emitter.send(message);
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);   // browser went away
        }
    }
}