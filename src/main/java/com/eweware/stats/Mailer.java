package main.java.com.eweware.stats;

import main.java.com.eweware.service.base.error.SystemErrorException;
import main.java.com.eweware.service.base.mgr.ManagerState;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * @author rk@post.harvard.edu
 *         Date: 9/15/13 Time: 1:05 PM
 */
public class Mailer {

    private static final Logger logger = Logger.getLogger(Mailer.class.getCanonicalName());

    private static Session _session;
    private static Properties props = new Properties();
    private static final String REPLY = "noreply@blahgua.com";

    static {
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", "email-smtp.us-east-1.amazonaws.com");
        props.put("mail.smtp.port", "587");
        _session = Session.getInstance(props,
                new javax.mail.Authenticator() {
                    @Override
                    public PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication("AKIAJBHKAWZ4ZZGDAQ2A", "Aq0SfQ54/HgNwpSpMjIMOI7dkukCvyoyYzk/4RoIwTPz");
                    }
                });
    }

    /**
     * Send a message
     * @param recipients Comma-separated list of recipients
     * @param subject  The subject
     * @param body The body (you may use html markup)
     * @throws MessagingException
     */
    public static void send(String recipients, String subject, String body) throws MessagingException {
        if (_session == null || recipients == null || subject == null || body == null) {
            logger.severe("WARNING: MailManager msg invalid or smtp session not started");
            return;
        }
        final MimeMessage message = new MimeMessage(_session);

        message.setFrom(new InternetAddress(REPLY));
        message.setRecipients(Message.RecipientType.TO, recipients);

        message.setSubject(subject);
        message.setContent(body, "text/html; charset=utf-8");
//        helper.setText(body);
        Transport.send(message);
    }
}
