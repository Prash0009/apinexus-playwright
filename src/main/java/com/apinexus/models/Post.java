package com.apinexus.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Post — Java POJO (Plain Old Java Object) that maps to a JSONPlaceholder
 * post object.
 *
 * Jackson uses field names to match JSON keys by default.
 * The @JsonProperty annotation is used when the Java field name differs
 * from the JSON key name.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) tells Jackson to silently skip
 * any JSON keys that don't have a corresponding Java field, preventing
 * UnrecognizedPropertyException when the API adds new fields in the future.
 *
 * Sample JSON from GET /posts/1:
 * {
 *   "userId": 1,
 *   "id": 1,
 *   "title": "sunt aut facere ...",
 *   "body": "quia et suscipit ..."
 * }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Post {

    // Maps to the "userId" key in JSON.
    // @JsonProperty is used because camelCase in Java corresponds to
    // the exact key name in JSON ("userId" matches "userId").
    @JsonProperty("userId")
    private int userId;

    // Maps to the "id" key — the post's unique identifier.
    @JsonProperty("id")
    private int id;

    // Maps to the "title" key — the post's title string.
    @JsonProperty("title")
    private String title;

    // Maps to the "body" key — the post's content string.
    @JsonProperty("body")
    private String body;

    // ── No-arg constructor ────────────────────────────────────────────────
    // Jackson requires a no-arg constructor to instantiate the POJO during
    // deserialization (JSON → Java object).
    public Post() {}

    // ── All-args constructor ───────────────────────────────────────────────
    // Convenient for creating Post objects in tests without setters.
    public Post(int userId, String title, String body) {
        this.userId = userId;
        this.title  = title;
        this.body   = body;
    }

    // ── Getters ───────────────────────────────────────────────────────────
    public int    getUserId() { return userId; }
    public int    getId()     { return id; }
    public String getTitle()  { return title; }
    public String getBody()   { return body; }

    // ── Setters ───────────────────────────────────────────────────────────
    // Setters allow Jackson to populate fields during deserialization even
    // though the fields are private.
    public void setUserId(int userId) { this.userId = userId; }
    public void setId(int id)         { this.id = id; }
    public void setTitle(String title){ this.title = title; }
    public void setBody(String body)  { this.body = body; }

    // ── toString ──────────────────────────────────────────────────────────
    @Override
    public String toString() {
        return "Post{userId=" + userId + ", id=" + id
                + ", title='" + title + "', body='" + body + "'}";
    }
}
