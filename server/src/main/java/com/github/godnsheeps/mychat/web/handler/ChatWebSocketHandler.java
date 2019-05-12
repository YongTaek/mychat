package com.github.godnsheeps.mychat.web.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.godnsheeps.mychat.MyChatServerApplication;
import com.github.godnsheeps.mychat.domain.ChatRepository;
import com.github.godnsheeps.mychat.domain.ContentRepository;
import com.github.godnsheeps.mychat.domain.MessageRepository;
import com.github.godnsheeps.mychat.domain.UserRepository;
import com.github.godnsheeps.mychat.util.Functions;
import com.github.godnsheeps.mychat.util.MessagesPatcher;
import io.jsonwebtoken.Jwts;
import lombok.Builder;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ChatWebSocketHandler
 *
 * @author jcooky
 * @since 2019-01-27
 */
@Component
public class ChatWebSocketHandler implements WebSocketHandler {
    private static Logger log = Loggers.getLogger(ChatWebSocketHandler.class);

    private List<WebSocketSession> sessions = Collections.synchronizedList(new ArrayList<>());

    private ObjectMapper objectMapper;
    private MessageRepository messageRepository;
    private ChatRepository chatRepository;
    private UserRepository userRepository;
    private ContentRepository contentRepository;
    private MessagesPatcher messagesPatcher;

    private String mentionRegex = "\\B@([\\S]+)";
    private Pattern pattern = Pattern.compile(mentionRegex);

    @Autowired
    public ChatWebSocketHandler(ObjectMapper objectMapper,
                                MessageRepository messageRepository,
                                ChatRepository chatRepository,
                                UserRepository userRepository,
                                ContentRepository contentRepository,
                                MessagesPatcher messagesPatcher) {
        this.objectMapper = objectMapper;
        this.messageRepository = messageRepository;
        this.chatRepository = chatRepository;
        this.userRepository = userRepository;
        this.contentRepository = contentRepository;
        this.messagesPatcher = messagesPatcher;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        sessions.add(session);

        log.trace("sessions: {}", sessions.size());

        return session.send(
                chatRepository.findById(MyChatServerApplication.rootChatId)
                        .flux()
                        .flatMap(chat -> messageRepository.findByChat(chat))
                        .flatMap(message -> Flux.fromIterable(message.getContents())
                                .map(content ->
                                        ResponseContent.builder()
                                                .isUser(content.isUser())
                                                .text(content.isUser() ? content.getUser().getName() : content.getText())
                                                .build())
                                .collectList()
                                .map(content ->
                                        ResponsePayload.builder()
                                                .id(message.getId())
                                                .username(message.getFrom().getName())
                                                .unReadUsers(message.getReadUsers())
                                                .contents(content)
                                                .build())
                                .single())
                        .map(Functions.wrapError(objectMapper::writeValueAsString))
                        .map(session::textMessage))
                .then(session
                        .receive()
                        .doOnComplete(() -> {
                            sessions.remove(session);
                        })
                        .map(Functions.wrapError(m -> objectMapper.readValue(m.getPayloadAsText(), MessageRequestPayload.class)))
                        .flatMap(payload -> {
                            var userId = Jwts.parser().setSigningKey(MyChatServerApplication.SECRET_KEY)
                                    .parseClaimsJws(payload.fromToken)
                                    .getBody().getId();
                            return userRepository.findById(userId)
                                    .flatMap(user -> messagesPatcher.run(user, payload.patchRequest));
                        })
                        .flatMap(message -> Flux.fromIterable(message.getContents())
                                .map(content ->
                                        ResponseContent.builder()
                                                .isUser(content.isUser())
                                                .text(content.isUser() ? content.getUser().getName() : content.getText())
                                                .build())
                                .collectList()
                                .map(content ->
                                        ResponsePayload.builder()
                                                .id(message.getId())
                                                .username(message.getFrom().getName())
                                                .unReadUsers(message.getReadUsers())
                                                .contents(content)
                                                .build())
                        )
                        .map(Functions.wrapError(objectMapper::writeValueAsString))
                        .flatMap(m -> Flux.fromStream(sessions.stream())
                                .flatMap(s -> {
                                    return s.send(Mono.just(s.textMessage(m)));
                                }))
                        .log(log)
                        .collectList()
                )
                .then();
    }

    @Data
    public static class MessageRequestPayload {
        String fromToken;
        MessagesPatcher.RequestPayload patchRequest;
    }

    @Data
    @Builder
    public static class ResponsePayload {
        String id;
        String username;
        Map<String, Boolean> unReadUsers;
        List<ResponseContent> contents;
    }


    @Data
    @Builder
    public static class ResponseContent {
        boolean isUser;
        String text;
        String id;
    }

}
