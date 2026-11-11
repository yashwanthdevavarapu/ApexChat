package com.apexchat.user;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RegistrationFormTest {

  private static Validator validator;

  @BeforeAll
  static void setUpValidator() {
    validator = Validation.buildDefaultValidatorFactory().getValidator();
  }

  private static RegistrationForm form(String username, String password, String confirm) {
    RegistrationForm form = new RegistrationForm();
    form.setUsername(username);
    form.setPassword(password);
    form.setConfirmPassword(confirm);
    return form;
  }

  private static Set<String> invalidFields(RegistrationForm form) {
    return validator.validate(form).stream()
        .map(ConstraintViolation::getPropertyPath)
        .map(Object::toString)
        .collect(Collectors.toSet());
  }

  @Test
  void acceptsAValidForm() {
    assertThat(invalidFields(form("maya_dev", "s3cure-pass", "s3cure-pass"))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"ab", "has space", "dash-name", "way_too_long_username_123", "émile"})
  void rejectsBadUsernames(String username) {
    assertThat(invalidFields(form(username, "s3cure-pass", "s3cure-pass")))
        .containsExactly("username");
  }

  @Test
  void rejectsShortPasswords() {
    assertThat(invalidFields(form("maya", "short", "short"))).containsExactly("password");
  }

  @Test
  void rejectsMismatchedConfirmation() {
    assertThat(invalidFields(form("maya", "s3cure-pass", "different")))
        .containsExactly("passwordConfirmed");
  }

  @Test
  void normalizesUsernames() {
    assertThat(UserService.normalize("  MaYa ")).isEqualTo("maya");
    assertThat(UserService.normalize(null)).isEmpty();
  }
}
