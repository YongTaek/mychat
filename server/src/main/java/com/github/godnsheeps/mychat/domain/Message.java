package com.github.godnsheeps.mychat.domain;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.util.Pair;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author jcooky
 */
@Document
@Data
@Builder
public class Message {
    @Id
    private String id;

    @DBRef
    private User from;

    private Long timestamp = System.currentTimeMillis();

    @DBRef
    private Chat chat;

    private List<Content> contents;

    private List<Mention> mentions;

    private Map<String, Boolean> readUsers;

    public Message addReadUser(User user) {
        this.readUsers.put(user.getId(), true);
        return this;
    }

    public Message addUnReadUsers(List<User> users) {
        this.readUsers.putAll(users.stream().map(user -> Pair.of(user.getId(), false)).collect(Collectors.toMap(Pair::getFirst, Pair::getSecond)));
        return this;
    }
}
