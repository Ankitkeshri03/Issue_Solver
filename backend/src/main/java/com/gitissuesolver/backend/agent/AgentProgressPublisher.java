package com.gitissuesolver.backend.agent;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fan-out of live agent progress to any SSE clients subscribed to a given ticket. */
@Component
@EnableScheduling
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

        // Flush headers immediately, even if the last job update preceded subscription.
        send(emitter, SseEmitter.event().name("connected").data(Map.of("status", "connected")), emitters);

        return emitter;
    }

    public void publish(Long ticketId, AgentStep step) {
        List<SseEmitter> emitters = emittersByTicket.get(ticketId);
        if (emitters == null) return;

        for (SseEmitter emitter : emitters) {
            send(emitter, SseEmitter.event().name("agent-step").id(String.valueOf(step.getId())).data(step), emitters);
        }
    }

    public void complete(Long ticketId) {
        List<SseEmitter> emitters = emittersByTicket.remove(ticketId);
        if (emitters == null) return;
        for (SseEmitter emitter : emitters) {
            send(emitter, SseEmitter.event().name("phase-complete").data(Map.of("status", "complete")), emitters);
            emitter.complete();
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        emittersByTicket.forEach((id, emitters) -> {
            for (SseEmitter emitter : emitters) {
                send(emitter, SseEmitter.event().comment("keep-alive"), emitters);
            }
            // Keep the list registered until completion; removing an empty list here
            // could race with a new subscriber adding itself to that same list.
        });
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event, List<SseEmitter> emitters) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException ex) {
            emitters.remove(emitter);
            // An IOException already causes the servlet container to complete dispatch.
            // Calling completeWithError here can cause a second dispatch of a committed response.
        }
    }
}
