package com.seastella.identity.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    Optional<UserToken> findByTokenHash(String tokenHash);

    List<UserToken> findByUserIdAndPurpose(Long userId, UserToken.Purpose purpose);

    boolean existsByUserIdAndPurpose(Long userId, UserToken.Purpose purpose);

    boolean existsByUserIdAndPurposeAndUsedAtIsNotNull(Long userId, UserToken.Purpose purpose);

    /** Links a person requested for themselves ("forgot password") since a moment. */
    long countByUserIdAndPurposeAndCreatedByUserIdIsNullAndCreatedAtAfter(Long userId, UserToken.Purpose purpose,
                                                                          java.time.Instant since);
}
