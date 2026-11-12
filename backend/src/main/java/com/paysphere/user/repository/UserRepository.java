package com.paysphere.user.repository;

import com.paysphere.user.entity.User;
import com.paysphere.user.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * User Repository — Spring Data JPA
 *
 * By extending JpaRepository<User, UUID>, Spring generates ALL standard
 * database operations at startup — no SQL needed for simple operations:
 *
 *   save(user)          → INSERT or UPDATE
 *   findById(id)        → SELECT WHERE id = ?
 *   findAll()           → SELECT * (use with caution — paginate!)
 *   delete(user)        → DELETE WHERE id = ?
 *   count()             → SELECT COUNT(*)
 *   existsById(id)      → SELECT EXISTS(...)
 *
 * Custom query methods: Spring Data generates SQL from the method name.
 * "findByEmail" → "SELECT * FROM users WHERE email = ?"
 * No SQL needed — Spring parses the method name and builds the query.
 *
 * For complex queries: use @Query with JPQL or native SQL.
 *
 * @Repository → marks this as a Spring bean and enables exception translation
 *   (converts low-level SQL exceptions to Spring's DataAccessException hierarchy)
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Find a user by their email address.
     * Used during login to look up the account before verifying the password.
     *
     * Returns Optional<User> — not User directly — because the user might not exist.
     * Callers must handle the "not found" case explicitly:
     *   userRepository.findByEmail(email)
     *       .orElseThrow(() -> new UserNotFoundException(email));
     */
    Optional<User> findByEmail(String email);

    /**
     * Check if an email is already registered.
     * Used during registration to prevent duplicates.
     * More efficient than findByEmail() because it uses SELECT EXISTS.
     */
    boolean existsByEmail(String email);

    /**
     * Count active users — useful for admin reporting.
     * Generates: SELECT COUNT(*) FROM users WHERE status = 'ACTIVE'
     */
    long countByStatus(UserStatus status);
}
