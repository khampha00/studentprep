package com.studentprep.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

public class AuthenticatedUserDetails implements UserDetails {

    private final UUID userId;
    private final String username;
    private final String pinHash;
    private final String role;
    private final boolean pinResetRequired;
    private final Collection<? extends GrantedAuthority> authorities;

    public AuthenticatedUserDetails(
            UUID userId,
            String username,
            String pinHash,
            String role,
            boolean pinResetRequired) {
        this.userId = userId;
        this.username = username;
        this.pinHash = pinHash;
        this.role = role;
        this.pinResetRequired = pinResetRequired;
        this.authorities = Collections.singletonList(new SimpleGrantedAuthority(role));
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    public boolean isPinResetRequired() {
        return pinResetRequired;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @JsonIgnore
    @Override
    public String getPassword() {
        return pinHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
