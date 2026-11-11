package com.apexchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.apexchat.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserRepository users;
  @Autowired PasswordEncoder encoder;

  @Test
  void registrationStoresANormalizedUserWithAHashedPassword() throws Exception {
    mvc.perform(
            post("/register")
                .with(csrf())
                .param("username", "Flow_User")
                .param("password", "flow-password")
                .param("confirmPassword", "flow-password"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login"))
        .andExpect(flash().attribute("registered", "flow_user"));

    var saved = users.findByUsername("flow_user").orElseThrow();
    assertThat(saved.getPasswordHash()).startsWith("$2").isNotEqualTo("flow-password");
    assertThat(encoder.matches("flow-password", saved.getPasswordHash())).isTrue();
  }

  @Test
  void invalidRegistrationRedisplaysTheForm() throws Exception {
    mvc.perform(
            post("/register")
                .with(csrf())
                .param("username", "x")
                .param("password", "short")
                .param("confirmPassword", "other"))
        .andExpect(status().isOk())
        .andExpect(view().name("register"))
        .andExpect(model().attributeHasFieldErrors("form", "username", "password"));
  }

  @Test
  void duplicateUsernamesAreRejectedCaseInsensitively() throws Exception {
    for (String name : new String[] {"dup_check", "DUP_CHECK"}) {
      mvc.perform(
          post("/register")
              .with(csrf())
              .param("username", name)
              .param("password", "dup-password")
              .param("confirmPassword", "dup-password"));
    }
    assertThat(users.findAll()).filteredOn(u -> u.getUsername().equals("dup_check")).hasSize(1);

    mvc.perform(
            post("/register")
                .with(csrf())
                .param("username", "Dup_Check")
                .param("password", "dup-password")
                .param("confirmPassword", "dup-password"))
        .andExpect(view().name("register"))
        .andExpect(model().attributeHasFieldErrorCode("form", "username", "USERNAME_TAKEN"));
  }

  @Test
  void formLoginSucceedsAndFails() throws Exception {
    mvc.perform(
        post("/register")
            .with(csrf())
            .param("username", "login_flow")
            .param("password", "login-password")
            .param("confirmPassword", "login-password"));

    mvc.perform(formLogin("/login").user("login_flow").password("login-password"))
        .andExpect(authenticated().withUsername("login_flow"))
        .andExpect(redirectedUrl("/chat"));

    mvc.perform(formLogin("/login").user("login_flow").password("wrong"))
        .andExpect(unauthenticated())
        .andExpect(redirectedUrl("/login?error"));
  }

  @Test
  void loginAndRegisterPagesRender() throws Exception {
    mvc.perform(get("/login"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Welcome back")));
    mvc.perform(get("/register"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Create your account")));
  }

  @Test
  @WithMockUser("page_user")
  void chatPageEmbedsUserAndCsrfForTheStompClient() throws Exception {
    mvc.perform(get("/chat"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-user=\"page_user\"")))
        .andExpect(content().string(containsString("data-csrf-header=\"X-CSRF-TOKEN\"")))
        .andExpect(content().string(containsString("data-csrf-token=")));
    mvc.perform(get("/")).andExpect(redirectedUrl("/chat"));
  }
}
