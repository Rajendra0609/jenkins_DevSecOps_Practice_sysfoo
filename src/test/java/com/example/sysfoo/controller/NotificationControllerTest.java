package com.example.sysfoo.controller;

import com.example.sysfoo.model.User;
import com.example.sysfoo.repository.UserRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.Properties;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BUG FIX (test coverage gap): NotificationController had no dedicated test
// before this pass — including no test at all for the SECURITY FIX that
// changed it from accepting a raw "to" address to resolving the recipient's
// email server-side by username (see recipientEmailIsResolvedServerSide,
// the direct regression test for that fix).
@WebMvcTest(NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
public class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JavaMailSender mailSender;

    @MockBean
    private UserRepository userRepository;

    @Test
    @WithMockUser(username = "alice")
    public void rejectsBlankUsername() throws Exception {
        mockMvc.perform(post("/api/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUsername\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A recipient username is required"));
    }

    @Test
    @WithMockUser(username = "alice")
    public void rejectsUnknownRecipient() throws Exception {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUsername\":\"ghost\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That user has no email on file"));
    }

    @Test
    @WithMockUser(username = "alice")
    public void rejectsRecipientWithNoEmailOnFile() throws Exception {
        User bob = new User("bob", "hashed", null, "Bob");
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));

        mockMvc.perform(post("/api/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUsername\":\"bob\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That user has no email on file"));
    }

    // SECURITY regression test: the recipient's email always comes from the
    // user directory looked up by username, never anything the client could
    // supply directly — the request body here deliberately has no email
    // field at all (see NotificationController's javadoc on the
    // open-mail-relay fix this replaced).
    @Test
    @WithMockUser(username = "alice")
    public void recipientEmailIsResolvedServerSide() throws Exception {
        User bob = new User("bob", "hashed", "bob@example.com", "Bob");
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));

        Session session = Session.getDefaultInstance(new Properties());
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(session));

        mockMvc.perform(post("/api/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUsername\":\"bob\",\"subject\":\"Hi\",\"taskText\":\"Do the thing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sent"))
                .andExpect(jsonPath("$.message").value("Notification sent to bob@example.com"));
    }
}
