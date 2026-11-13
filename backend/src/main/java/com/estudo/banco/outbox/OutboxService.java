package com.estudo.banco.outbox;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Escreve eventos no outbox.
 *
 * Chamado de DENTRO da transacao de negocio, de proposito: e isso que torna o
 * evento e o fato indivisiveis.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository eventos;
    private final ObjectMapper mapper;

    public OutboxService(OutboxEventRepository eventos, ObjectMapper mapper) {
        this.eventos = eventos;
        this.mapper = mapper;
    }

    public void registrar(String tipo, Object payload) {
        eventos.save(new OutboxEvent(tipo, mapper.writeValueAsString(payload)));
    }
}
