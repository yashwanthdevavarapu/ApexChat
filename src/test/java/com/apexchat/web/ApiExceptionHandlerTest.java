package com.apexchat.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.apexchat.common.ConflictException;
import com.apexchat.common.InvalidRequestException;
import com.apexchat.common.NotFoundException;
import com.apexchat.common.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiExceptionHandlerTest {

  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  void mapsDomainExceptionsToHttpStatus() {
    assertThat(ApiExceptionHandler.statusFor(NotFoundException.room("x")))
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(ApiExceptionHandler.statusFor(new ConflictException("C", "c")))
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(ApiExceptionHandler.statusFor(new RateLimitExceededException()))
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(ApiExceptionHandler.statusFor(new InvalidRequestException("I", "i")))
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void problemDetailCarriesTheStableCode() {
    var response = handler.handle(NotFoundException.user("ghost"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getDetail()).isEqualTo("User 'ghost' does not exist");
    assertThat(response.getBody().getProperties()).containsEntry("code", "USER_NOT_FOUND");
  }
}
