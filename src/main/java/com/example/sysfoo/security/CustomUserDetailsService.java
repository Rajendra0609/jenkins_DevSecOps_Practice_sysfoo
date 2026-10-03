package com.example.sysfoo.security;

import com.example.sysfoo.model.User;
import com.example.sysfoo.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Bridges our {@link User} entity to Spring Security's authentication machinery.
 * Looked up by username on every login attempt.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("No account found for username: " + username));

        // SECURITY FIX: accountLocked now reflects User.isAccountLocked() (see
        // AccountSecurityService) — DaoAuthenticationProvider checks this
        // BEFORE comparing the password and throws LockedException, which
        // AuthController surfaces as a distinct, clear "try again later"
        // message instead of a generic "invalid credentials".
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPassword())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .accountLocked(user.isAccountLocked())
                .build();
    }
}
