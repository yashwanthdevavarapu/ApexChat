package com.apexchat.web;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the single-page chat shell. Everything after first paint happens over STOMP. */
@Controller
public class PageController {

  @GetMapping("/")
  public String root() {
    return "redirect:/chat";
  }

  @GetMapping("/chat")
  public String chat(Model model, Principal principal) {
    model.addAttribute("username", principal.getName());
    return "chat";
  }
}
