package com.github.godnsheeps.mychat.web.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.godnsheeps.mychat.MyChatServerApplication;
import com.github.godnsheeps.mychat.domain.*;
import com.github.godnsheeps.mychat.util.Functions;
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

import java.io.IOException;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

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

    private String mentionRegex = "\\B@([\\S]+)";
    private Pattern pattern = Pattern.compile(mentionRegex);

    @Autowired
    public ChatWebSocketHandler(ObjectMapper objectMapper,
                                MessageRepository messageRepository,
                                ChatRepository chatRepository,
                                UserRepository userRepository,
                                ContentRepository contentRepository) {
        this.objectMapper = objectMapper;
        this.messageRepository = messageRepository;
        this.chatRepository = chatRepository;
        this.userRepository = userRepository;
        this.contentRepository = contentRepository;
    }


    public IRequestPayload parsePayload(String message) throws IOException {
        try {
            return objectMapper.readValue(message, MessageRequestPayload.class);
        } catch (IOException e) {
            e.printStackTrace();
            return objectMapper.readValue(message, ReadRequestPayload.class);
        }
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
                                                .unReadCount(message.getReadUsers().entrySet().stream().filter(e -> !e.getValue()).count())
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
                        .map(Functions.wrapError(m -> parsePayload(m.getPayloadAsText())))
                        .flatMap(payload -> {
                            if (payload instanceof MessageRequestPayload) {
                                return messageReceive((MessageRequestPayload) payload);
                            } else {
                                return readMessage((ReadRequestPayload) payload);
                            }
                        })
                        .flatMap(m -> Flux.fromStream(sessions.stream())
                                .flatMap(s -> {
                                    return s.send(Mono.just(s.textMessage(m)));
                                }))
                        .log(log)
                        .collectList()
                )
                .then();
    }

    private Flux<String> readMessage(ReadRequestPayload payload) {
        var userId = Jwts.parser().setSigningKey(MyChatServerApplication.SECRET_KEY)
                .parseClaimsJws(payload.fromToken)
                .getBody().getId();
        return userRepository.findById(userId)
                .flux()
                .flatMap(user -> messageRepository.findAll()
                        .filter(message ->
                                message.getReadUsers().entrySet().stream().filter(p -> p.getKey().equals(user.getId()) && !p.getValue()).count() != 0)
                        .map(message -> message.addReadUser(user))
                )
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
                                        .unReadCount(message.getReadUsers().entrySet().stream().filter(e -> !e.getValue()).count())
                                        .contents(content)
                                        .build())
                )
                .map(Functions.wrapError(objectMapper::writeValueAsString));
    }

    private Mono<String> messageReceive(MessageRequestPayload payload) {
        var userId = Jwts.parser().setSigningKey(MyChatServerApplication.SECRET_KEY)
                .parseClaimsJws(payload.fromToken)
                .getBody().getId();
        return Mono.zip(userRepository.findById(userId),
                chatRepository.findById(MyChatServerApplication.rootChatId),
                Mono.just(payload))
                .flatMap(t -> {
                    var message = t.getT3().message;
                    var m = pattern.matcher(message);
                    var messageBuilder = Message.builder()
                            .chat(t.getT2())
                            .from(t.getT1());
                    var tokens = message.split(mentionRegex);
                    int i = 0;
                    List<Mono<Content>> contents = new LinkedList();
                    contents.add(Mono.just(Content.builder().isUser(false).text(tokens[0]).build()));
                    while (m.find()) {
                        var name = m.group();
                        contents.add(userRepository.findByName(name.substring(1))
                                .map(user -> Content.builder().isUser(true).user(user).build())
                                .switchIfEmpty(Mono.just(Content.builder().isUser(false).text(name).build())));
                        if (tokens.length > ++i) {
                            contents.add(Mono.just(Content.builder().isUser(false).text(tokens[i]).build()));
                        }
                    }
                    return Flux.mergeSequential(contents)
                            .flatMap(content -> contentRepository.save(content))
                            .collectList()
                            .map(contentList -> {
                                List<Mention> mentions = contentList.stream().filter(content -> content.isUser())
                                        .map(content -> Mention.builder().user(content.getUser()).isRead(false).build())
                                        .collect(Collectors.toList());
                                return messageBuilder.mentions(mentions).contents(contentList).readUsers(new HashMap<>()).build();
                            })
                            .flatMap(newMessage ->
                                    userRepository.findAll()
                                            .filter(user -> user.getId() != t.getT1().getId())
                                            .collectList()
                                            .map(users -> newMessage.addUnReadUsers(users).addReadUser(t.getT1()))
                            );
                })
                .flatMap(message -> messageRepository.save(message))
                .flatMap(t -> Flux.fromIterable(t.getContents())
                        .map(content ->
                                ResponseContent.builder()
                                        .isUser(content.isUser())
                                        .text(content.isUser() ? content.getUser().getName() : content.getText())
                                        .build())
                        .collectList()
                        .map(content ->
                                ResponsePayload.builder()
                                        .id(t.getId())
                                        .username(t.getFrom().getName())
                                        .unReadCount(t.getReadUsers().entrySet().stream().filter(e -> !e.getValue()).count())
                                        .contents(content)
                                        .build())
                )
                .map(Functions.wrapError(objectMapper::writeValueAsString));
    }

    public interface IRequestPayload {

    }

    @Data
    public static class MessageRequestPayload implements IRequestPayload {
        String fromToken;
        String message;
    }

    @Data
    public static class ReadRequestPayload implements IRequestPayload {
        String fromToken;
    }

    @Data
    @Builder
    public static class ResponsePayload {
        String id;
        String username;
        long unReadCount;
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
