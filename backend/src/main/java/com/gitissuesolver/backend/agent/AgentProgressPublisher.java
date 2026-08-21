package com.gitissuesolver.backend.agent;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fan-out of live agent progress to any SSE clients subscribed to a given ticket. */
@Component
public class AgentProgressPublisher {

    private final Map<Long, List<SseEmitter>> emittersByTicket = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long ticketId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> emitters = emittersByTicket.computeIfAbsent(ticketId, id -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);

        Runnable cleanup = () -> emitters.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        return emitter;
    }

    public void publish(Long ticketId, AgentStep step) {
        List<SseEmitter> emitters = emittersByTicket.get(ticketId);
        if (emitters == null) return;

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("agent-step").data(step));
            } catch (IOException e) {
                emitter.complete();
                emitters.remove(emitter);
            }
        }
    }

    public void complete(Long ticketId) {
        List<SseEmitter> emitters = emittersByTicket.remove(ticketId);
        if (emitters == null) return;
        emitters.forEach(SseEmitter::complete);
    }
}
