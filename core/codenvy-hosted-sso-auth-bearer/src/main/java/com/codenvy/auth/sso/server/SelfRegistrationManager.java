/*
 *  [2012] - [2016] Codenvy, S.A.
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Codenvy S.A. and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Codenvy S.A.
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Codenvy S.A..
 */
package com.codenvy.auth.sso.server;

import com.codenvy.mail.MailSenderClient;
import com.codenvy.mail.shared.dto.AttachmentDto;
import com.codenvy.mail.shared.dto.EmailBeanDto;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;

import org.eclipse.che.api.core.ApiException;
import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.UnauthorizedException;
import org.eclipse.che.api.core.model.user.User;
import org.eclipse.che.api.user.server.Constants;
import org.eclipse.che.api.user.server.UserManager;
import org.eclipse.che.api.user.server.UserService;
import org.eclipse.che.api.user.server.model.impl.UserImpl;
import org.eclipse.che.api.user.server.spi.PreferenceDao;
import org.eclipse.che.commons.lang.Deserializer;
import org.eclipse.che.commons.lang.NameGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static javax.ws.rs.core.MediaType.TEXT_HTML;
import static org.eclipse.che.commons.lang.IoUtil.getResource;
import static org.eclipse.che.commons.lang.IoUtil.readAndCloseQuietly;
import static org.eclipse.che.dto.server.DtoFactory.newDto;

/**
 * Created by sj on 14.06.16.
 */
public class SelfRegistrationManager {
    private static final Logger LOG = LoggerFactory.getLogger(SelfRegistrationManager.class);

    // TODO made this configurable
    private static final String MAIL_TEMPLATE = "email-templates/verify_email_address.html";
    private static final String LOGO          = "/email-templates/header.png";
    private static final String LOGO_CID      = "codenvyLogo";

    @Inject
    BearerTokenManager tokenManager;
    @Inject
    MailSenderClient   mailSenderClient;
    @Inject
    @Named("mailsender.application.from.email.address")
    String             mailFrom;
    @Inject
    UserManager        userManager;
    @Inject
    PreferenceDao      preferenceDao;
    @Inject
    @Named(UserService.USER_SELF_CREATION_ALLOWED)
    boolean            userSelfCreationAllowed;


    public void createUser(String token)
            throws InvalidBearerTokenException, ServerException, ConflictException, NotFoundException, IOException {
           Map<String, String> payload = tokenManager.getPayload(token);
           createUser(payload.get("email"), payload.get("userName"), payload.get("password"));
    }

    public void createUser(String email, String userName, String password)
            throws IOException, ConflictException, ServerException, NotFoundException {
        if (!userSelfCreationAllowed) {
            throw new ConflictException("Currently only admins can create accounts. Please contact our Admin Team for further info.");
        }
        final String availableUsername = userName == null ? findAvailableUsername(email) : findAvailableUsername(userName);
        final String id = NameGenerator.generate(User.class.getSimpleName().toLowerCase(), Constants.ID_LENGTH);
        final String userPassword = password == null ?  UUID.randomUUID().toString().replace("-", "").substring(0, 12) : password;
        final User user = new UserImpl(id, email, availableUsername, userPassword, Collections.emptyList());
        userManager.create(user, false);

        final Map<String, String> preferences = preferenceDao.getPreferences(id);
        preferences.putAll(ImmutableMap.of(
//                "firstName", firstName,
//                "lastName", lastName,
                "email", email));
        preferenceDao.setPreferences(id, preferences);


    }

    private String findAvailableUsername(String source) throws IOException {
        String candidate = source.contains("@") ? source.substring(0, source.indexOf('@')) : source;
        int count = 1;
        while (getUserByName(candidate).isPresent()) {
            candidate = candidate.concat(String.valueOf(count++));
        }
        return candidate;
    }

    private Optional<User> getUserByName(String name) throws IOException {
        try {
            User user = userManager.getByName(name);
            return Optional.of(user);
        } catch (NotFoundException e) {
            return Optional.empty();
        } catch (ServerException e) {
            throw new IOException(e.getLocalizedMessage(), e);
        }
    }

    public void sendVerificationEmail(SelfRegistrationService.ValidationData validationData, String queryParams, String masterHostUrl)
            throws IOException {
        try {
            Map<String, String> props = new HashMap<>();
            props.put("logo.cid", "codenvyLogo");
            props.put("bearertoken", tokenManager.generateBearerToken(
                    ImmutableMap.of(
                            "initiator", "email",
                            "email", validationData.getEmail(),
                            "username", validationData.getUserName(),
                            "password", validationData.getPassword()
                                   )));
            props.put("additional.query.params", queryParams);
            props.put("com.codenvy.masterhost.url", masterHostUrl);

            File logo = new File(this.getClass().getResource(LOGO).getPath());
            AttachmentDto attachmentDto = newDto(AttachmentDto.class)
                    .withContent(Base64.getEncoder().encodeToString(Files.toByteArray(logo)))
                    .withContentId(LOGO_CID)
                    .withFileName("logo.png");

            EmailBeanDto emailBeanDto = newDto(EmailBeanDto.class)
                    .withBody(Deserializer.resolveVariables(readAndCloseQuietly(getResource("/" + MAIL_TEMPLATE)), props))
                    .withFrom(mailFrom)
                    .withTo(validationData.getEmail())
                    .withReplyTo(null)
                    .withSubject("Verify Your Codenvy Account")
                    .withMimeType(TEXT_HTML)
                    .withAttachments(Collections.singletonList(attachmentDto));


            mailSenderClient.sendMail(emailBeanDto);
        } catch (ApiException e) {
            LOG.warn("Unable to send confirmation email", e);
            throw new IOException("Not able to send confirmation email", e);
        }
    }
}
