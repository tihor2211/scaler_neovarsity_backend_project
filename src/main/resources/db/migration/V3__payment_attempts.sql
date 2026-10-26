-- Razorpay maps one Razorpay order to one payment attempt, so an order can have several payments
-- (e.g. a failed card, then UPI). Replace the one-payment-per-order unique key with a plain index.
ALTER TABLE payments ADD KEY idx_payments_order (order_id);
ALTER TABLE payments DROP INDEX uk_payments_order;
