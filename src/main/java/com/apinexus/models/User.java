package com.apinexus.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * User — POJO for ReqRes user objects inside the "data" wrapper.
 *
 * Sample JSON (GET https://reqres.in/api/users/2):
 * {
 *   "data": {
 *     "id": 2,
 *     "email": "janet.weaver@reqres.in",
 *     "first_name": "Janet",
 *     "last_name": "Weaver",
 *     "avatar": "https://reqres.in/img/faces/2-image.jpg"
 *   }
 * }
 *
 * This class represents only the inner "data" object.
 * The outer wrapper is handled in the test by navigating to the "data" key.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class User {

    @JsonProperty("id")
    private int id;

    // JSON key is "email" — maps directly.
    @JsonProperty("email")
    private String email;

    // JSON uses snake_case ("first_name") but Java uses camelCase,
    // so @JsonProperty bridges the naming convention difference.
    @JsonProperty("first_name")
    private String firstName;

    @JsonProperty("last_name")
    private String lastName;

    // Avatar is a full URL to the user's profile picture.
    @JsonProperty("avatar")
    private String avatar;

    // ── No-arg constructor for Jackson deserialization ────────────────────
    public User() {}

    // ── Getters ───────────────────────────────────────────────────────────
    public int    getId()        { return id; }
    public String getEmail()     { return email; }
    public String getFirstName() { return firstName; }
    public String getLastName()  { return lastName; }
    public String getAvatar()    { return avatar; }

    // ── Setters ───────────────────────────────────────────────────────────
    public void setId(int id)               { this.id = id; }
    public void setEmail(String email)      { this.email = email; }
    public void setFirstName(String name)   { this.firstName = name; }
    public void setLastName(String name)    { this.lastName = name; }
    public void setAvatar(String avatar)    { this.avatar = avatar; }

    @Override
    public String toString() {
        return "User{id=" + id + ", email='" + email + "', name='" + firstName
                + " " + lastName + "'}";
    }
}
