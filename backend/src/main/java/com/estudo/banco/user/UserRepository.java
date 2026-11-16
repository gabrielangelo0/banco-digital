package com.estudo.banco.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * O e-mail e gravado em minusculas, entao quem consulta normaliza antes.
     * A restricao no banco e por `lower(email)` e cobre o resto.
     */
    Optional<User> findByEmail(String email);
}
