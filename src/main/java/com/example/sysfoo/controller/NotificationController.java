package com.example.sysfoo.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.bind.annotation.*;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;

/**
 * REST endpoint: POST /api/notify
 *
 * Sends an HTML email notification when a task is added in the Sysfoo dashboard.
 *
 * ── Spring Boot setup required ────────────────────────────────────────────────
 *
 * 1. pom.xml — add dependency:
 *    <dependency>
 *      <groupId>org.springframework.boot</groupId>
 *      <artifactId>spring-boot-starter-mail</artifactId>
 *    </dependency>
 *
 * 2. application.properties (or per-profile):
 *
 *    # ── Gmail SMTP (use App Password, not your real password) ──
 *    spring.mail.host=smtp.gmail.com
 *    spring.mail.port=587
 *    spring.mail.username=your-email@gmail.com
 *    spring.mail.password=your-app-password
 *    spring.mail.properties.mail.smtp.auth=true
 *    spring.mail.properties.mail.smtp.starttls.enable=true
 *
 *    # ── Custom sender name shown in inbox ──
 *    sysfoo.mail.from=Sysfoo Dashboard <your-email@gmail.com>
 *
 *    # ── Optional: restrict to specific SMTP providers ──
 *    # spring.mail.host=smtp.office365.com   (Outlook/M365)
 *    # spring.mail.host=email-smtp.us-east-1.amazonaws.com  (SES)
 *
 * ── How it works ──────────────────────────────────────────────────────────────
 *  Frontend (index.html) calls: POST /api/notify
 *  Body (JSON):
 *    {
 *      "to":       "assignee@company.com",
 *      "subject":  "[Sysfoo] New Task Assigned: Fix login bug",
 *      "taskText": "Fix login bug on the staging server",
 *      "name":     "Raja",
 *      "priority": "high",
 *      "time":     "14:35",
 *      "date":     "18 Apr"
 *    }
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class NotificationController {

    // ── FIX: required=false allows the application context to start even when
    // no SMTP configuration is present (e.g. test profile, local dev without mail).
    // The endpoint returns a 503 instead of crashing at startup.
    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${sysfoo.mail.from:Sysfoo Dashboard <noreply@sysfoo.dev>}")
    private String fromAddress;

    @PostMapping("/notify")
    public ResponseEntity<Map<String, String>> sendNotification(
            @RequestBody Map<String, String> payload) {

        // Guard: mail is not configured in this environment
        if (mailSender == null) {
            return ResponseEntity.status(503)
                    .body(Map.of("status", "error",
                                 "message", "Mail service is not configured in this environment"));
        }

        String to       = payload.getOrDefault("to", "");
        String subject  = payload.getOrDefault("subject", "[Sysfoo] New Task Notification");
        String taskText = payload.getOrDefault("taskText", "(no description)");
        String name     = payload.getOrDefault("name", "Team");
        String priority = payload.getOrDefault("priority", "medium");
        String time     = payload.getOrDefault("time", "");
        String date     = payload.getOrDefault("date", "");

        if (to.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Recipient email is required"));
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(buildHtmlEmail(name, taskText, priority, time, date), true);

            mailSender.send(message);

            return ResponseEntity.ok(Map.of(
                    "status",  "sent",
                    "message", "Notification sent to " + to
            ));

        } catch (MessagingException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ── HTML email template ───────────────────────────────────────────────────
    private String buildHtmlEmail(String name, String taskText,
                                  String priority, String time, String date) {

        String priorityColor = switch (priority.toLowerCase()) {
            case "high"   -> "#f87171";
            case "low"    -> "#4ade80";
            default       -> "#fbbf24";  // medium
        };

        String priorityEmoji = switch (priority.toLowerCase()) {
            case "high"   -> "🔴";
            case "low"    -> "🟢";
            default       -> "🟡";
        };

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8"/>
              <meta name="viewport" content="width=device-width,initial-scale=1"/>
              <title>Task Notification</title>
            </head>
            <body style="margin:0;padding:0;background:#07090f;font-family:'Segoe UI',Arial,sans-serif;">
              <table width="100%%" cellpadding="0" cellspacing="0" style="background:#07090f;padding:40px 20px;">
                <tr>
                  <td align="center">
                    <table width="580" cellpadding="0" cellspacing="0"
                           style="background:#0d1117;border:1px solid rgba(255,255,255,0.08);
                                  border-radius:16px;overflow:hidden;max-width:580px;">

                      <!-- Header -->
                      <tr>
                        <td style="background:linear-gradient(135deg,#0d2b24,#0d1117);
                                   padding:30px 36px;border-bottom:1px solid rgba(255,255,255,0.07);">
                          <table width="100%%" cellpadding="0" cellspacing="0">
                            <tr>
                              <td>
                                <div style="font-size:22px;font-weight:800;color:#e8edf5;letter-spacing:-0.5px;">
                                  Sys<span style="color:#00d4aa;">foo</span>
                                </div>
                                <div style="font-size:11px;color:#4e637a;letter-spacing:0.12em;
                                            text-transform:uppercase;margin-top:4px;">
                                  Task Notification
                                </div>
                              </td>
                              <td align="right">
                                <div style="background:rgba(0,212,170,0.12);border:1px solid rgba(0,212,170,0.3);
                                            border-radius:8px;padding:8px 14px;display:inline-block;">
                                  <span style="color:#00d4aa;font-size:11px;font-weight:700;
                                               text-transform:uppercase;letter-spacing:0.1em;">
                                    New Task
                                  </span>
                                </div>
                              </td>
                            </tr>
                          </table>
                        </td>
                      </tr>

                      <!-- Body -->
                      <tr>
                        <td style="padding:32px 36px;">

                          <p style="color:#94a3b8;font-size:14px;margin:0 0 24px;">
                            Hi <strong style="color:#e8edf5;">%s</strong>,
                            a new task has been assigned to you on the Sysfoo dashboard.
                          </p>

                          <!-- Task card -->
                          <div style="background:#111827;border:1px solid rgba(255,255,255,0.08);
                                      border-left:4px solid %s;border-radius:10px;padding:20px 22px;
                                      margin-bottom:24px;">
                            <div style="color:#4e637a;font-size:10px;text-transform:uppercase;
                                        letter-spacing:0.12em;margin-bottom:8px;">Task</div>
                            <div style="color:#e8edf5;font-size:16px;font-weight:600;
                                        line-height:1.5;margin-bottom:16px;">
                              %s
                            </div>
                            <table cellpadding="0" cellspacing="0">
                              <tr>
                                <td style="padding-right:20px;">
                                  <div style="color:#4e637a;font-size:10px;text-transform:uppercase;
                                              letter-spacing:0.1em;margin-bottom:4px;">Priority</div>
                                  <div style="color:%s;font-size:12px;font-weight:700;
                                              text-transform:uppercase;">
                                    %s %s
                                  </div>
                                </td>
                                <td style="padding-right:20px;">
                                  <div style="color:#4e637a;font-size:10px;text-transform:uppercase;
                                              letter-spacing:0.1em;margin-bottom:4px;">Date</div>
                                  <div style="color:#94a3b8;font-size:12px;">%s</div>
                                </td>
                                <td>
                                  <div style="color:#4e637a;font-size:10px;text-transform:uppercase;
                                              letter-spacing:0.1em;margin-bottom:4px;">Time</div>
                                  <div style="color:#94a3b8;font-size:12px;">%s</div>
                                </td>
                              </tr>
                            </table>
                          </div>

                          <p style="color:#4e637a;font-size:12px;margin:0;">
                            You received this notification because you were added as an assignee
                            in the Sysfoo dashboard. Log in to view your full task list.
                          </p>
                        </td>
                      </tr>

                      <!-- Footer -->
                      <tr>
                        <td style="padding:18px 36px;background:#07090f;
                                   border-top:1px solid rgba(255,255,255,0.06);">
                          <p style="color:#4e637a;font-size:11px;margin:0;text-align:center;">
                            Sent by <strong style="color:#00d4aa;">Sysfoo Dashboard</strong>
                            · Auto-generated notification
                          </p>
                        </td>
                      </tr>

                    </table>
                  </td>
                </tr>
              </table>
            </body>
            </html>
            """.formatted(name, priorityColor, taskText,
                          priorityColor, priorityEmoji, priority.toUpperCase(),
                          date, time);
    }
}