-- FR-10: "my orders" is read newest first, one page at a time. With this index PostgreSQL reads the first page
-- straight from the index instead of sorting every order of the customer.
CREATE INDEX idx_orders_customer_created ON orders (customer_id, created_at DESC);
