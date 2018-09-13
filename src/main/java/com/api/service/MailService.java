package com.api.service;

import com.api.model.MailMessage;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;

import java.io.IOException;
import java.util.List;

public interface MailService {

    /**
     * List all Messages of the user's mailbox matching the query.
     *
     * @param service Authorized Gmail API instance.
     * @param userId  User's email address. The special value "me"
     *                can be used to indicate the authenticated user.
     * @param query   String used to filter the Messages listed.
     * @throws IOException throws when
     */
    List<Message> listMessagesMatchingQuery(Gmail service,
                                            String userId, String query) throws IOException;

    /**
     * Save the attachments in a given email.
     *
     * @param service       Authorized Gmail API instance.
     * @param userId        User's email address. The special value "me"
     *                      can be used to indicate the authenticated user.
     * @param messageId     ID of Message containing attachment.
     * @param attachmentId  ID of Attachment.
     * @param filename      name of file in attachment.
     * @param directoryPath path of directory to store attachments
     * @throws IOException throws when
     */
    void storeAttachments(Gmail service,
                          String userId, String messageId, String attachmentId,
                          String filename, String directoryPath) throws IOException;

    MailMessage compileMailMessage(Gmail service, String userId, Message message) throws IOException;

    String getQueryForMultipleLabelsSearching(Gmail service, String userId);

    String getAttachmentDirectoryPath();

    String getDirectoryPath();
}
