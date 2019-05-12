package com.github.godnsheeps.mychat.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.godnsheeps.mychat.MyChatServerApplication;
import com.github.godnsheeps.mychat.domain.*;
import com.github.godnsheeps.mychat.web.handler.ChatWebSocketHandler;
import io.jsonwebtoken.Jwts;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class MessagesPatcher {

    public final static String MESSAGE_CREATE = "create";
    public final static String MESSAGE_READ = "read";

    private UserRepository userRepository;
    private MessageRepository messageRepository;
    private ContentRepository contentRepository;
    private ChatRepository chatRepository;
    private ObjectMapper objectMapper;

    private String mentionRegex = "\\B@([\\S]+)";
    private Pattern pattern = Pattern.compile(mentionRegex);

    @Autowired
    public MessagesPatcher(ObjectMapper objectMapper, UserRepository userRepository, MessageRepository messageRepository,
                           ContentRepository contentRepository, ChatRepository chatRepository) {
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.contentRepository = contentRepository;
        this.chatRepository = chatRepository;
    }

    public Optional<RequestPayload> parse(String request) {
        try {
            return Optional.of(objectMapper.readValue(request, RequestPayload.class));
        } catch (IOException e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }

    public Mono<Message> createMessage(User from, String message) {
        return chatRepository.findById(MyChatServerApplication.rootChatId)
                .flatMap(chat -> {
                    var m = pattern.matcher(message);
                    var messageBuilder = Message.builder()
                            .chat(chat)
                            .from(from);
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
                                            .filter(user -> user.getId() != from.getId())
                                            .collectList()
                                            .map(users -> newMessage.addUnReadUsers(users).addReadUser(from))
                            )
                            .flatMap(messageRepository::save);
                });
    }

//    public Mono<Message> readMessage(User user, String target, String value) {
//
//    }

    private Mono<Message> readMessage(User user, String target) {
        System.out.println(target);
        return messageRepository.findById(target)
                .filter(message -> message.getReadUsers().entrySet().stream().filter(p -> p.getKey().equals(user.getId()) && !p.getValue()).count() != 0)
                .map(message -> message.addReadUser(user))
                .flatMap(message -> messageRepository.save(message));
    }

    public Mono<Message> run(User user, RequestPayload request) {
        switch (request.op) {
            case MESSAGE_CREATE:
                return createMessage(user, String.valueOf(request.value));
            case MESSAGE_READ:
                return readMessage(user, request.target);
            default:
                return Mono.empty();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestPayload {
        String op;
        String target;
        Object value;
    }
}
