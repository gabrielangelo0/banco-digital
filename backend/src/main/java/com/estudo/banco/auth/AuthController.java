package com.estudo.banco.auth;

import com.estudo.banco.auth.dto.RegisterRequest;
import com.estudo.banco.auth.dto.RegisterResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegistrationService registration;

    public AuthController(RegistrationService registration) {
        this.registration = registration;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse registrar(@Valid @RequestBody RegisterRequest request) {
        Long contaId = registration.register(
                request.email(), request.password(), request.transactionPassword());
        return new RegisterResponse(contaId);
    }
}
