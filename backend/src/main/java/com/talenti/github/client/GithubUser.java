package com.talenti.github.client;

/** La cuenta de GitHub: id numérico (el que manda, 013-05), {@code login} y tipo. */
public record GithubUser(long id, String login, String type) {
}
