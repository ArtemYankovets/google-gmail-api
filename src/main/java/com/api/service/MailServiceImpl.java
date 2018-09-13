package com.api.service;

import com.api.model.MailMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.repackaged.org.apache.commons.codec.binary.Base64;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.*;
import lombok.Getter;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Log
@Service
@Component(value = "MailServiceImpl")
public class MailServiceImpl implements MailService {

    private static final String HEADERS = "headers";
    private static final String VALUE = "value";

    private static Set<String> nameOfAttachments;
    private static long attachmentNumber;

    private static long mailCounter;

    @Getter
    private String attachmentDirectoryPath;

    @Value("${regex.pattern.value.in.parentheses}")
    private String regexValueInParentheses;

    @Getter
    @Value("${store.directory.path}")
    private String directoryPath;

    @Value("${store.attachment.directory.name}")
    private String attachmentDirectoryName;

    @Value("${gmail.filter.query}")
    private String query;


    @PostConstruct
    public void init() {
        nameOfAttachments = new HashSet<>();
        attachmentDirectoryPath = directoryPath + "/" + attachmentDirectoryName;
        createStorage(attachmentDirectoryPath);
    }

    public List<Message> listMessagesMatchingQuery(Gmail service, String userId,
                                                   String query) throws IOException {
        ListMessagesResponse response = service.users().messages().list(userId).setQ(query).execute();

        List<Message> messages = new ArrayList<>();
        while (response.getMessages() != null) {
            messages.addAll(response.getMessages());
            if (response.getNextPageToken() != null) {
                String pageToken = response.getNextPageToken();
                response = service.users().messages().list(userId).setQ(query)
                        .setPageToken(pageToken).execute();
            } else {
                break;
            }
        }
        return messages;
    }

    public void storeAttachments(Gmail service, String userId, String messageId, String attachmentId,
                                 String filename, String directoryPath) throws IOException {

        MessagePartBody attachPart = service.users().messages().attachments().
                get(userId, messageId, attachmentId).execute();

        byte[] fileByteArray = Base64.decodeBase64(attachPart.getData());

        String filePath = directoryPath + "/" + filename;
        try (FileOutputStream fileOutFile = new FileOutputStream(filePath)) {
            fileOutFile.write(fileByteArray);
        }
    }

    public MailMessage compileMailMessage(Gmail client, String userId, Message message) throws IOException {

        MailMessage mailMessage = new MailMessage();

        JsonNode messageNode;
        ObjectMapper mapper = new ObjectMapper();
        messageNode = mapper.readTree(message.toPrettyString());

        // setting message id
        mailMessage.setId(message.getId());

        // getting mail body
        MessagePart payload = message.getPayload();
        String mailBody = "";
        if ("multipart/alternative".equals(payload.getMimeType())) {
            List<String> payloadParts = payload.getParts().stream()
                    .filter(p -> "text/plain".equals(p.getMimeType()))
                    .map(p -> new String(p.getBody().decodeData(), StandardCharsets.UTF_8))
                    .collect(Collectors.toList());

            if (!payloadParts.isEmpty()) {
                mailBody = payloadParts.get(0);
            }
        } else if ("text/plain".equals(payload.getMimeType())) {
            mailBody = new String(payload.getBody().decodeData(), StandardCharsets.UTF_8);
        }

        mailMessage.setBody(mailBody);

        // getting date
        JsonNode messagePayloadNode = messageNode.get("payload");
        Optional<JsonNode> jsonNode = getJsonNodeByField(messagePayloadNode,
                "Date");
        jsonNode.ifPresent(jsonNode1 -> mailMessage.setDate(jsonNode1.get(VALUE).asText()));

        // getting subject
        jsonNode = getJsonNodeByField(messagePayloadNode,
                "Subject");
        jsonNode.ifPresent(jsonNode1 -> mailMessage.setSubject(jsonNode1.get(VALUE).asText()));

        // getting attachments
        List<MessagePart> parts = message.getPayload().getParts();
        if (parts != null && !parts.isEmpty()) {
            StringBuilder mailAttachmentFileNames = new StringBuilder();
            for (MessagePart part : parts) {
                String attachmentId = part.getBody().getAttachmentId();

                String attachmentFilename = part.getFilename();
                if (attachmentId != null && !attachmentFilename.isEmpty()) {

                    String regex = "(.+?)(\\.[^.]*$|$)";
                    String pureFileName = "";
                    String fileNameExtension = "";
                    Matcher m = Pattern.compile(regex).matcher(attachmentFilename);
                    if (m.matches()) {
                        pureFileName = m.group(1);
                        fileNameExtension = attachmentFilename.substring(pureFileName.length());
                    }

                    if (nameOfAttachments.contains(pureFileName)) {
                        //todo attachment number should inc for the in same filename ranges
                        attachmentNumber++;
                        attachmentFilename = pureFileName + "_" + attachmentNumber + fileNameExtension;

                    }
                    nameOfAttachments.add(pureFileName);
                    mailAttachmentFileNames.append(" ").append(attachmentFilename);

                    // saving attachment file to local storage
                    storeAttachments(client, userId, message.getId(), attachmentId,
                            attachmentFilename, attachmentDirectoryPath);
                }
            }
            mailMessage.setAttachments(mailAttachmentFileNames.toString());
        }

        // getting from
        jsonNode = getJsonNodeByField(messagePayloadNode,
                "From");
        jsonNode.ifPresent(jsonNode1 -> mailMessage.setFrom(jsonNode1.get(VALUE).asText()));
        // getting to
        jsonNode = getJsonNodeByField(messagePayloadNode,
                "To");
        jsonNode.ifPresent(jsonNode1 -> mailMessage.setTo(jsonNode1.get(VALUE).asText()));

        log.info("Mail massage #" + ++mailCounter);

        return mailMessage;
    }

    public String getQueryForMultipleLabelsSearching(Gmail service, String userId) {
        String basicLabel = getBasicLabelForMultipleLabels(query);

        String multipleLabels = "";
        try {
            ListLabelsResponse listResponse = service.users().labels().list(userId).execute();
            List<Label> labels = listResponse.getLabels();
            if (labels.isEmpty()) {
                log.info("No labels found.");
            } else {
                if (!basicLabel.isEmpty()) {
                    multipleLabels = labels.stream()
                            .map(l -> {
                                String name = l.getName();
                                name.replaceAll("[\\s\\-()]", "_");
                                return name;
                            })
                            .filter(l -> l.contains(basicLabel))
                            .collect(Collectors.joining("|"));

                }

                String collectedLabels = labels.stream().
                        map(Label::getName).
                        collect(Collectors.joining(System.lineSeparator() + "\t"));

                log.info(System.lineSeparator() + "Labels: " + System.lineSeparator() + "\t" + collectedLabels);
            }
        } catch (IOException e) {
            log.warning("Get labels list request fail." + System.lineSeparator() +
                    "Exception message is " + e.getMessage());
        }
        String newQuery;
        if (multipleLabels.isEmpty()) {
            newQuery = query;
        } else {
            newQuery = String.format("'label:(%s)'", multipleLabels);
        }
        return newQuery;
    }

    /**
     * Method compile query for selection mails from basic label and all it sub labels
     *
     * @param basicQuery - basic query
     * @return query for selection mails from basic label
     */
    private String getBasicLabelForMultipleLabels(String basicQuery) {
        String basicLabel = "";
        String basicLabelInQuery;
        Matcher m = Pattern.compile(regexValueInParentheses).matcher(basicQuery);
        if (m.find()) {
            basicLabelInQuery = m.group(1);
            if (basicLabelInQuery.contains("*")) {
                basicLabel = basicLabelInQuery.substring(0, basicLabelInQuery.length() - 1);
            }
        }
        return basicLabel;
    }

    private void createStorage(String attachmentDirectoryPath) {
        Path path = Paths.get(attachmentDirectoryPath);

        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
                log.info("Storage created " + Paths.get(attachmentDirectoryPath).toAbsolutePath().toString());
            } catch (IOException e) {
                log.warning("Storage creating process FAIL" + System.lineSeparator() +
                        "Exception: " + e.getMessage());
            }
        }
    }

    private Optional<JsonNode> getJsonNodeByField(JsonNode requestJsonNode, String fieldValue) {
        return StreamSupport.stream(requestJsonNode.get(MailServiceImpl.HEADERS).spliterator(), false)
                .filter(node -> fieldValue.equals(node.get("name").asText()))
                .findFirst();
    }
}
