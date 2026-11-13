# Evidência: o deadlock que a ordenação de locks elimina

Execução de `TransferConcurrencyTest` com a ordenação de locks REMOVIDA de propósito
em `TransferService.execute` (travando origem→destino em vez de por id crescente).
50 transferências A→B e 50 B→A, disparadas ao mesmo tempo.

```
2026-11-13T14:25:38.342-03:00  WARN 13370 --- [banco] [pool-3-thread-3] org.hibernate.orm.jdbc.error             : HHH000247: ErrorCode: 0, SQLState: 40P01
2026-11-13T14:25:38.342-03:00  WARN 13370 --- [banco] [ool-3-thread-12] org.hibernate.orm.jdbc.error             : HHH000247: ErrorCode: 0, SQLState: 40P01
2026-11-13T14:25:38.343-03:00  WARN 13370 --- [banco] [pool-3-thread-3] org.hibernate.orm.jdbc.error             : ERROR: deadlock detected
2026-11-13T14:25:38.342-03:00  WARN 13370 --- [banco] [pool-3-thread-7] org.hibernate.orm.jdbc.error             : HHH000247: ErrorCode: 0, SQLState: 40P01
2026-11-13T14:25:38.343-03:00  WARN 13370 --- [banco] [ool-3-thread-12] org.hibernate.orm.jdbc.error             : ERROR: deadlock detected
2026-11-13T14:25:38.343-03:00  WARN 13370 --- [banco] [pool-3-thread-7] org.hibernate.orm.jdbc.error             : ERROR: deadlock detected
...
[ERROR] Tests run: 5, Failures: 1, Errors: 1, Skipped: 0
```

Com a ordenação restaurada (`Math.min`/`Math.max` sobre os ids), a mesma carga passa
sem um único deadlock. O arquivo completo desta execução está em
`.superpowers/sdd/2026-11-13-banco-digital/red-task5-deadlock.log` (ignorado pelo git).
