package com.example.sysfoo.repository;

import com.example.sysfoo.model.VerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, Long> {

    Optional<VerificationToken> findByTokenAndPurpose(String token, String purpose);

    List<VerificationToken> findAllByUserIdAndPurposeAndUsedFalse(Long userId, String purpose);
}
