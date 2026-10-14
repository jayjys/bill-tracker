package com.billtracker.user;

import com.billtracker.household.Household;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name")
    private String displayName;

    /** Random token that identifies this user's forwarding address (bills+TOKEN@domain). */
    @Column(name = "inbound_token", nullable = false, unique = true)
    private String inboundToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Loaded eagerly: almost every request needs it, and views render after the transaction closes. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "household_id")
    private Household household;

    protected AppUser() {
    }

    public AppUser(String email, String passwordHash, String displayName, String inboundToken,
                   Household household) {
        this.household = household;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.inboundToken = inboundToken;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getInboundToken() {
        return inboundToken;
    }

    public void setInboundToken(String inboundToken) {
        this.inboundToken = inboundToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Household getHousehold() {
        return household;
    }

    public void setHousehold(Household household) {
        this.household = household;
    }

    /** Name to show other household members. */
    public String getShortName() {
        return displayName != null ? displayName : email.substring(0, email.indexOf('@'));
    }
}
