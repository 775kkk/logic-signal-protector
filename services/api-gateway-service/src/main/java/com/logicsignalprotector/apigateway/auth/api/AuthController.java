package com.logicsignalprotector.apigateway.auth.api;

import com.logicsignalprotector.apigateway.auth.domain.UserEntity;
import com.logicsignalprotector.apigateway.auth.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    // DTO-запрос (Java record — просто удобный неизменяемый контейнер данных)
    public record RegisterRequest(String login, String password) {}

    // DTO-ответ
    public record RegisterResponse(Long id, String login) {}

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        try {
            UserEntity user = userService.register(request.login(), request.password());
            RegisterResponse response = new RegisterResponse(user.getId(), user.getLogin());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }
}
