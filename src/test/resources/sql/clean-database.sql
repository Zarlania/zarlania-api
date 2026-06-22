-- Resets committed integration-test data between @SpringBootTest test methods.
-- Delete in foreign-key-safe order: child tables first, then parents.
DELETE FROM memberships;
DELETE FROM organizations;
DELETE FROM users;
