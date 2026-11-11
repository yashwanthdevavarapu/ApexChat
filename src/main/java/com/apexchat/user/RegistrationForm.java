package com.apexchat.user;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Form-backing object for the sign-up page. */
public class RegistrationForm {

  @NotBlank(message = "Username is required")
  @Pattern(regexp = "^[A-Za-z0-9_]{3,20}$", message = "Use 3-20 letters, digits or underscores")
  private String username;

  @NotBlank(message = "Password is required")
  @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
  private String password;

  private String confirmPassword;

  /** BCrypt only uses the first 72 <em>bytes</em>; Spring Security rejects longer input. */
  @AssertTrue(message = "Password is too long (max 72 bytes; non-Latin characters count as 2-4)")
  public boolean isPasswordWithinHashLimit() {
    return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
  }

  @AssertTrue(message = "Passwords do not match")
  public boolean isPasswordConfirmed() {
    return Objects.equals(password, confirmPassword);
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getConfirmPassword() {
    return confirmPassword;
  }

  public void setConfirmPassword(String confirmPassword) {
    this.confirmPassword = confirmPassword;
  }
}
