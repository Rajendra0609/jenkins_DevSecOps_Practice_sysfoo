package com.example.sysfoo.service;

import com.example.sysfoo.model.User;
import com.example.sysfoo.model.VerificationToken;
import com.example.sysfoo.repository.VerificationTokenRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * Issues, emails, and redeems the single-use tokens behind the
 * forgot-password and verify-your-email flows.
 *
 * ── Why this doesn't reveal whether an account/email exists ─────────────
 * AuthController deliberately returns the same "if that address is
 * registered, we've sent a link" response whether or not the email matches
 * an account — this method is what makes that possible: it's a no-op (does
 * nothing, sends nothing) when there's no match, rather than the caller
 * needing to branch on "found vs not found" and risk leaking that in the
 * response or in timing.
 */
@Service
public class TokenService {

    private static final int TOKEN_BYTES = 32; // 256 bits of entropy
    private static final long RESET_TOKEN_TTL_MINUTES = 30;
    private static final long VERIFY_TOKEN_TTL_HOURS = 48;

    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    private VerificationTokenRepository tokenRepository;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${sysfoo.mail.from:Sysfoo Dashboard <noreply@sysfoo.dev>}")
    private String fromAddress;

    /** Base URL the frontend is served from, used to build clickable links in emails. */
    @Value("${app.frontend-base-url:http://localhost:8080}")
    private String frontendBaseUrl;

    public boolean isMailConfigured() {
        return mailSender != null;
    }

    /** Issues a password-reset token and emails it, if mail is configured and the user has an email on file. */
    public void issuePasswordResetToken(User user) {
        if (mailSender == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        invalidateExisting(user.getId(), VerificationToken.PURPOSE_RESET_PASSWORD);
        String token = newToken();
        tokenRepository.save(new VerificationToken(
                token, user.getId(), VerificationToken.PURPOSE_RESET_PASSWORD,
                LocalDateTime.now().plusMinutes(RESET_TOKEN_TTL_MINUTES)));

        String link = frontendBaseUrl + "/login.html?resetToken=" + token;
        String name = (user.getDisplayName() != null && !user.getDisplayName().isBlank()) ? user.getDisplayName() : user.getUsername();
        sendLinkEmail(user.getEmail(), "[Sysfoo] Reset your password",
                "Reset your password", name,
                "We received a request to reset the password for your Sysfoo account. "
                        + "This link expires in " + RESET_TOKEN_TTL_MINUTES + " minutes and can only be used once. "
                        + "If you didn't request this, you can safely ignore this email.",
                link, "Reset Password");
    }

    /** Issues an email-verification token and emails it. */
    public void issueEmailVerificationToken(User user) {
        if (mailSender == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        invalidateExisting(user.getId(), VerificationToken.PURPOSE_VERIFY_EMAIL);
        String token = newToken();
        tokenRepository.save(new VerificationToken(
                token, user.getId(), VerificationToken.PURPOSE_VERIFY_EMAIL,
                LocalDateTime.now().plusHours(VERIFY_TOKEN_TTL_HOURS)));

        String link = frontendBaseUrl + "/api/auth/verify-email?token=" + token;
        String name = (user.getDisplayName() != null && !user.getDisplayName().isBlank()) ? user.getDisplayName() : user.getUsername();
        sendLinkEmail(user.getEmail(), "[Sysfoo] Verify your email address",
                "Verify your email", name,
                "Please confirm this is your email address to finish setting up your Sysfoo account. "
                        + "This link expires in " + VERIFY_TOKEN_TTL_HOURS + " hours.",
                link, "Verify Email");
    }

    /**
     * Looks up a token WITHOUT consuming it, so a caller can validate
     * everything else about the request (e.g. new-password strength)
     * before deciding whether to spend it — see AuthController.resetPassword,
     * which only calls {@link #markUsed} once the new password has actually
     * been accepted and saved. A token that's found-but-not-yet-marked-used
     * remains valid for a retry, e.g. after a rejected weak password.
     */
    public java.util.Optional<VerificationToken> find(String token, String purpose) {
        return tokenRepository.findByTokenAndPurpose(token, purpose)
                .filter(VerificationToken::isValid);
    }

    public void markUsed(VerificationToken token) {
        token.setUsed(true);
        tokenRepository.save(token);
    }

    private void invalidateExisting(Long userId, String purpose) {
        List<VerificationToken> existing = tokenRepository.findAllByUserIdAndPurposeAndUsedFalse(userId, purpose);
        existing.forEach(t -> t.setUsed(true));
        tokenRepository.saveAll(existing);
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void sendLinkEmail(String to, String subject, String heading, String name,
                                String body, String link, String buttonLabel) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText("""
                    <!DOCTYPE html>
                    <html><body style="margin:0;padding:0;background:#07090f;font-family:'Segoe UI',Arial,sans-serif;">
                      <table width="100%%" cellpadding="0" cellspacing="0" style="background:#07090f;padding:40px 20px;">
                        <tr><td align="center">
                          <table width="480" cellpadding="0" cellspacing="0" style="background:#0d1117;border:1px solid rgba(255,255,255,0.08);border-radius:16px;overflow:hidden;">
                            <tr><td style="padding:30px 36px;background:linear-gradient(135deg,#0d2b24,#0d1117);border-bottom:1px solid rgba(255,255,255,0.07);">
                              <div style="font-size:22px;font-weight:800;color:#e8edf5;">Sys<span style="color:#00d4aa;">foo</span></div>
                              <div style="font-size:11px;color:#4e637a;text-transform:uppercase;letter-spacing:0.12em;margin-top:4px;">%s</div>
                            </td></tr>
                            <tr><td style="padding:32px 36px;">
                              <p style="color:#94a3b8;font-size:14px;margin:0 0 20px;">Hi <strong style="color:#e8edf5;">%s</strong>,</p>
                              <p style="color:#94a3b8;font-size:14px;line-height:1.6;margin:0 0 24px;">%s</p>
                              <a href="%s" style="display:inline-block;background:#00d4aa;color:#07090f;font-weight:700;text-decoration:none;padding:12px 24px;border-radius:8px;font-size:14px;">%s</a>
                              <p style="color:#4e637a;font-size:12px;margin:24px 0 0;">If the button doesn't work, copy and paste this link: %s</p>
                            </td></tr>
                          </table>
                        </td></tr>
                      </table>
                    </body></html>
                    """.formatted(heading, name, body, link, buttonLabel, link), true);
            mailSender.send(message);
        } catch (MessagingException e) {
            // Best-effort — the caller (AuthController) never surfaces mail
            // delivery failures to the client for these flows, to avoid
            // leaking account-existence information via error responses.
        }
    }
}
