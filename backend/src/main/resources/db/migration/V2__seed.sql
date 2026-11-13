-- Contas de demonstracao, usadas pelo frontend. Os testes NAO dependem destas
-- linhas: cada teste cria as contas de que precisa, porque o schema e
-- compartilhado por toda a suite.
INSERT INTO accounts (holder_name, balance) VALUES
    ('Gabriel', 1000.00),
    ('Maria', 500.00);
