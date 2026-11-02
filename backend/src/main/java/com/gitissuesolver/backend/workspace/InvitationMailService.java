package com.gitissuesolver.backend.workspace;

import com.gitissuesolver.backend.auth.GmailApiEmailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class InvitationMailService {
    private final GmailApiEmailSender gmail;
    private final ObjectProvider<JavaMailSender> smtp;
    private final String provider, from;
    public InvitationMailService(GmailApiEmailSender gmail, ObjectProvider<JavaMailSender> smtp,
            @Value("${app.mail.provider:gmail_api}") String provider, @Value("${app.password-reset.from:}") String from) {
        this.gmail = gmail; this.smtp = smtp; this.provider = provider; this.from = from;
    }
    public boolean send(WorkspaceService.InvitationCreated invitation) {
        if (from.isBlank()) return false;
        String subject = "Invitation to " + invitation.workspaceName();
        String body = "You were invited to join " + invitation.workspaceName() + " in Issue Solver.\n\n"
                + "Open this link to activate your account with " + invitation.email() + " and choose your own password. "
                + "If you already have an account, sign in to accept instead:\n"
                + invitation.invitationUrl() + "\n\nThis invitation expires in 7 days and can be accepted once.";
        try {
            if ("gmail_api".equals(provider)) gmail.send(from, invitation.email(), subject, body);
            else {
                var sender = smtp.getIfAvailable(); if (sender == null) return false;
                var message = new SimpleMailMessage(); message.setFrom(from); message.setTo(invitation.email());
                message.setSubject(subject); message.setText(body); sender.send(message);
            }
            return true;
        } catch (RuntimeException ex) { return false; } // The admin can still copy the private link; never expose provider secrets.
    }
}
