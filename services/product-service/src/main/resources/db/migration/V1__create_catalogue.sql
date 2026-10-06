CREATE TABLE categories (
    id   BIGSERIAL    PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE products (
    id          BIGSERIAL      PRIMARY KEY,
    name        VARCHAR(200)   NOT NULL,
    description VARCHAR(2000),
    price       NUMERIC(12, 2) NOT NULL CHECK (price > 0),
    category_id BIGINT         NOT NULL REFERENCES categories (id),
    version     BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX idx_products_category_id ON products (category_id);
