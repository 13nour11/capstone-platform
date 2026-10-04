#!/bin/sh
# Database-per-service: one database and one owning login per service (runs only on the first start).
set -eu

create_db() {
  db="$1"; user="$2"; pass="$3"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v pass="$pass" <<EOSQL
CREATE USER $user WITH PASSWORD :'pass';
CREATE DATABASE $db OWNER $user;
REVOKE ALL ON DATABASE $db FROM PUBLIC;
EOSQL
}

create_db product_db   product_user   "$PRODUCT_DB_PASSWORD"
create_db inventory_db inventory_user "$INVENTORY_DB_PASSWORD"
create_db order_db     order_user     "$ORDER_DB_PASSWORD"
create_db payment_db   payment_user   "$PAYMENT_DB_PASSWORD"
