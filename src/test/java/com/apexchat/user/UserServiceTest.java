package com.apexchat.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.apexchat.common.ConflictException;
import com.apexchat.support.MutableClock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock private AppUserRepository users;
  private UserService service;

  @BeforeEach
  void setUp() {
    service =
        new UserService(
            users,
            new BCryptPasswordEncoder(4),
            new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));
  }

  @Test
  void registersNormalizedUsernameWithHashedPassword() {
    when(users.existsByUsername("maya")).thenReturn(false);
    when(users.saveAndFlush(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

    AppUser saved = service.register(" Maya ", "s3cure-pass");

    assertThat(saved.getUsername()).isEqualTo("maya");
    assertThat(saved.getPasswordHash()).startsWith("$2");
  }

  @Test
  void concurrentDuplicateHittingTheUniqueConstraintIsStillAConflict() {
    when(users.existsByUsername("maya")).thenReturn(false);
    when(users.saveAndFlush(any(AppUser.class)))
        .thenThrow(new DataIntegrityViolationException("uk_username"));

    assertThatThrownBy(() -> service.register("maya", "s3cure-pass"))
        .isInstanceOf(ConflictException.class)
        .extracting("code")
        .isEqualTo("USERNAME_TAKEN");
  }

  @Test
  void unknownUsersCannotAuthenticate() {
    when(users.findByUsername("ghost")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.loadUserByUsername("Ghost"))
        .isInstanceOf(UsernameNotFoundException.class);
  }
}
