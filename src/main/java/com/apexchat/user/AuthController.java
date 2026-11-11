package com.apexchat.user;

import com.apexchat.common.ConflictException;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Server-rendered login and sign-up pages. */
@Controller
public class AuthController {

  private final UserService userService;

  public AuthController(UserService userService) {
    this.userService = userService;
  }

  @GetMapping("/login")
  public String login() {
    return "login";
  }

  @GetMapping("/register")
  public String registerForm(Model model) {
    model.addAttribute("form", new RegistrationForm());
    return "register";
  }

  @PostMapping("/register")
  public String register(
      @Valid @ModelAttribute("form") RegistrationForm form,
      BindingResult result,
      RedirectAttributes redirect) {
    if (result.hasErrors()) {
      return "register";
    }
    try {
      userService.register(form.getUsername(), form.getPassword());
    } catch (ConflictException e) {
      result.rejectValue("username", e.code(), e.getMessage());
      return "register";
    }
    redirect.addFlashAttribute("registered", UserService.normalize(form.getUsername()));
    return "redirect:/login";
  }
}
