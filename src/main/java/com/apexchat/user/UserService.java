package com.apexchat.user;

import com.apexchat.common.ConflictException;
import java.time.Clock;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registers users and loads them for Spring Security authentication. */
@Service
public class UserService implements UserDetailsService {

  private final AppUserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;

  public UserService(AppUserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
  }

  /** Usernames are case-insensitive: "Alice" and "alice" are the same account. */
  public static String normalize(String username) {
    return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Creates an account. The pre-check gives a friendly error in the common case; the unique
   * constraint is the real guard when two sign-ups for the same name race.
   */
  public AppUser register(String username, String rawPassword) {
    String normalized = normalize(username);
    if (users.existsByUsername(normalized)) {
      throw usernameTaken();
    }
    try {
      return users.saveAndFlush(
          new AppUser(normalized, passwordEncoder.encode(rawPassword), clock.instant()));
    } catch (DataIntegrityViolationException e) {
      throw usernameTaken();
    }
  }

  private static ConflictException usernameTaken() {
    return new ConflictException("USERNAME_TAKEN", "That username is already taken");
  }

  @Transactional(readOnly = true)
  public boolean exists(String username) {
    return users.existsByUsername(normalize(username));
  }

  @Override
  @Transactional(readOnly = true)
  public UserDetails loadUserByUsername(String username) {
    return users
        .findByUsername(normalize(username))
        .map(
            u ->
                User.withUsername(u.getUsername())
                    .password(u.getPasswordHash())
                    .roles("USER")
                    .build())
        .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
  }
}
