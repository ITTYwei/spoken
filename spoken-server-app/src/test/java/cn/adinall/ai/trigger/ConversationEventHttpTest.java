package cn.adinall.ai.trigger;

import cn.adinall.ai.api.dto.ServerEventDTO;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.ConversationEventVO;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.Disposable;

import cn.adinall.ai.trigger.http.ConversationHttpAssembler;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import org.junit.Test;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class ConversationEventHttpTest {
    @Test public void wireEventsKeepOrderAndHeartbeatStopsWithBusinessStream() {
        AtomicInteger subscribed=new AtomicInteger();
        Flux<ConversationEventVO> source=Flux.just(ConversationEventVO.of(EventTypeEnum.SESSION_READY,Map.of("status","READY")),
                ConversationEventVO.of(EventTypeEnum.SESSION_CLOSED,Map.of())).doOnSubscribe(s -> subscribed.incrementAndGet());
        List<ServerEventDTO> output=ConversationHttpAssembler.events("s",source).collectList().block(Duration.ofSeconds(2));
        assertEquals(2,output.size());
        assertEquals("session.ready",output.get(0).type());
        assertEquals("session.closed",output.get(1).type());
        assertEquals(1,subscribed.get());
    }
    @Test public void disconnectCancelsBusinessSubscription() {
        AtomicBoolean cancelled=new AtomicBoolean();
        Disposable subscription=ConversationHttpAssembler.events("s",Flux.<ConversationEventVO>never()
                .doOnCancel(() -> cancelled.set(true))).subscribe();
        subscription.dispose();
        assertTrue(cancelled.get());
    }
}
