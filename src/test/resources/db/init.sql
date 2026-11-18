-- Test fixture: a schema the read-only role may read, and one it may not.
-- Runs as the container superuser; the server always connects as mcp_ro afterwards.
CREATE SCHEMA app;
CREATE TABLE app.users (
    id    int PRIMARY KEY,
    email text NOT NULL,
    name  text
);
INSERT INTO app.users
SELECT g, 'user' || g || '@example.com', 'User ' || g
FROM generate_series(1, 20) AS g;

CREATE TABLE app.orders (
    id      int PRIMARY KEY,
    user_id int NOT NULL REFERENCES app.users (id),
    total   numeric(10, 2) NOT NULL
);
INSERT INTO app.orders VALUES (1, 1, 9.99), (2, 2, 42.00), (3, 1, 3.50);

-- Deliberately not granted: metadata is visible, data is not.
CREATE SCHEMA secret;
CREATE TABLE secret.api_keys (
    id   int PRIMARY KEY,
    key  text NOT NULL
);
INSERT INTO secret.api_keys VALUES (1, 'sk-live-do-not-read');

CREATE ROLE mcp_ro LOGIN PASSWORD 'ropass';
GRANT CONNECT ON DATABASE mcp_test TO mcp_ro;
GRANT USAGE ON SCHEMA app TO mcp_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA app TO mcp_ro;
