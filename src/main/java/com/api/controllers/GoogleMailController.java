package com.api.controllers;

import com.api.model.MailMessage;
import com.api.scv.CSVFileWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets.Details;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.repackaged.org.apache.commons.codec.binary.Base64;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import lombok.extern.java.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import javax.mail.MessagingException;
import javax.servlet.http.HttpServletRequest;
import java.awt.*;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Log
@Controller
@RestController
public class GoogleMailController implements ApplicationListener<ApplicationReadyEvent> {

    private static final String APPLICATION_NAME = "SDK mail messages export project";
    private static final String HEADERS = "headers";
    private static final String VALUE = "value";
    private static HttpTransport httpTransport;
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static com.google.api.services.gmail.Gmail client;

    private Set<String> nameOfAttachments = new HashSet<>();
    private long attachmentNumber = 0;

    private String attachmentsPath;

    private GoogleClientSecrets clientSecrets;
    private GoogleAuthorizationCodeFlow flow;
    private Credential credential;

    private long mailCounter;

    private List<String> labelIds = new ArrayList<>();
    JSONArray rawMessages = new JSONArray();

    private String userId = "me";

    @Value("${gmail.client.clientId}")
    private String clientId;

    @Value("${gmail.client.clientSecret}")
    private String clientSecret;

    @Value("${launch.url}")
    private String startUri;

    @Value("${gmail.client.redirectUri}")
    private String redirectUri;

    @Value("${store.directory.path}")
    private String directoryPath;

    @Value("${store.csv.file.name}")
    private String csvFileName;

    @Value("${store.attachment.directory.name}")
    private String attachmentDirectoryName;

    @Value("${gmail.filter.query}")
    private String query;

    @Value("${regex.pattern.value.in.parentheses}")
    private String regexValueInParentheses;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {
        log.info("Application started ... launching browser now");
        launchBrowser(startUri);
    }

    @RequestMapping(value = "/login/gmail", method = RequestMethod.GET)
    public RedirectView googleConnectionStatus(HttpServletRequest request) throws Exception {
        return new RedirectView(authorize());
    }

    @RequestMapping(value = "/login/gmailCallback", method = RequestMethod.GET, params = "code")
    public ResponseEntity<String> oauth2Callback(@RequestParam(value = "code") String code) throws IOException {

        JSONObject rawMsgReq = new JSONObject();

        mailCounter = 0;

        try {
            TokenResponse response = flow.newTokenRequest(code).setRedirectUri(redirectUri).execute();
            log.info("Token Received");

            credential = flow.createAndStoreCredential(response, "userID");
            client = new com.google.api.services.gmail.Gmail.Builder(httpTransport, JSON_FACTORY, credential)
                    .setApplicationName(APPLICATION_NAME).build();

            String basicLabel = getBasicLabelForMultipleLabels(query);

            query = getQueryForMultipleLabelsSearching(client, userId, basicLabel);

            log.info("Searching query: " + query);

            final long startTime = System.currentTimeMillis();
            log.info("-----------------------------------------------------");

            List<Message> listOfMessages = listMessagesMatchingQuery(client, userId, query);

            List<MailMessage> mailMessageList = new ArrayList<>();

            if (listOfMessages != null) {

                log.info("Messages found: " + listOfMessages.size());
                createStorage(directoryPath);

                mailMessageList = listOfMessages.parallelStream()
                        .map(m -> {

                            try {
                                Message message = client.users().messages().get(userId, m.getId()).execute();
                                return compileMailMessage(message);
                            } catch (IOException | MessagingException e) {
                                e.printStackTrace();
                                return null;
                            }

                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                rawMsgReq.put("messages", rawMessages);

                String attachmentDirectoryPath = directoryPath + "/" + attachmentDirectoryName;
                log.info("Mail attachments are stored in \t"
                        + Paths.get(attachmentDirectoryPath).toAbsolutePath().toString());

                String filePath = directoryPath + "/" + csvFileName;
                CSVFileWriter.writeCSVFile(filePath, mailMessageList);

                log.info("Messages are stored in CSV file \t"
                        + Paths.get(filePath).toAbsolutePath().toString());
            } else {
                log.info("Messages not found ");
            }
            log.info("-----------------------------------------------------");
            final long endTime = System.currentTimeMillis();
            log.info("Total messages processed: " + mailMessageList.size());
            log.info("Total execution time: " + (endTime - startTime));
            log.info("-----------------------------------------------------");
        } catch (Exception e) {
            log.warning("exception cached: " + e.getMessage());
            e.printStackTrace();
        } finally {
            System.exit(0);
        }

        return new ResponseEntity<>(rawMsgReq.toString(), HttpStatus.OK);
    }

    private void launchBrowser(String url) {
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            try {
                desktop.browse(new URI(url));
            } catch (IOException | URISyntaxException e) {
                e.printStackTrace();
            }
        } else {
            Runtime runtime = Runtime.getRuntime();

            // http://lopica.sourceforge.net/os.html
            String os = System.getProperty("os.name").toLowerCase();
            try {
                if (os.contains("win")) {
                    // do not support links by format "leodev.html#someTag"
                    // if windows, open url via cmd
                    runtime.exec("rundll32 url.dll,FileProtocolHandler " + url);
                } else if (os.contains("mac")) {
                    runtime.exec("open " + url);
                } else if (os.contains("nix") || os.contains("nux")) {
                    String[] browsers = {"epiphany", "firefox", "mozilla", "konqueror", "netscape",
                            "opera", "links", "lynx"};
                    // String formatting with colling all browsers through '||' in shell terminal
                    // "browser0 "URI" || browser1 "URI" ||..."
                    StringBuilder cmd = new StringBuilder();
                    for (int i = 0; i < browsers.length; i++)
                        cmd.append(i == 0 ? "" : " || ").append(browsers[i]).append(" \"").append(url).append("\" ");
                    runtime.exec(new String[]{"sh", "-c", cmd.toString()});
                } else {
                    return;
                }
            } catch (IOException e) {
                log.warning("exception cached: " + e.getMessage());
            }
        }
    }

    private String authorize() throws Exception {
        AuthorizationCodeRequestUrl authorizationUrl;
        if (flow == null) {
            Details web = new Details();
            web.setClientId(clientId);
            web.setClientSecret(clientSecret);
            clientSecrets = new GoogleClientSecrets().setWeb(web);
            httpTransport = GoogleNetHttpTransport.newTrustedTransport();

            List<String> listOfScopes = new ArrayList<>();
            listOfScopes.add(GmailScopes.MAIL_GOOGLE_COM);
            listOfScopes.add(GmailScopes.GMAIL_MODIFY);
            listOfScopes.add(GmailScopes.GMAIL_READONLY);

            flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY, clientSecrets,
                    new ArrayList<>(listOfScopes)).build();
        }
        authorizationUrl = flow.newAuthorizationUrl().setRedirectUri(redirectUri);

        log.info("Gmail authorizationUrl -> " + authorizationUrl);
        return authorizationUrl.build();
    }

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

    private String getQueryForMultipleLabelsSearching(Gmail service, String userId, String basicLabel) {
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
                        map(l -> l.getName()).
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
     * List all Messages of the user's mailbox matching the query.
     *
     * @param service Authorized Gmail API instance.
     * @param userId  User's email address. The special value "me"
     *                can be used to indicate the authenticated user.
     * @param query   String used to filter the Messages listed.
     * @throws IOException
     */
    public static List<Message> listMessagesMatchingQuery(Gmail service, String userId,
                                                          String query) throws IOException {
        ListMessagesResponse response = service.users().messages().list(userId).setQ(query).execute();

        List<Message> messages = new ArrayList<Message>();
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
     * @throws IOException
     */
    private void storeAttachments(Gmail service, String userId, String messageId, String attachmentId,
                                  String filename, String directoryPath) throws IOException {

        MessagePartBody attachPart = service.users().messages().attachments().
                get(userId, messageId, attachmentId).execute();

        Base64 base64Url = new Base64(true);
        byte[] fileByteArray = base64Url.decodeBase64(attachPart.getData());

        String filePath = directoryPath + "/" + filename;
        try (FileOutputStream fileOutFile = new FileOutputStream(filePath)) {
            fileOutFile.write(fileByteArray);
        }
    }

    private void createStorage(String directoryPath) {
        attachmentsPath = directoryPath + "/" + attachmentDirectoryName;
        Path path = Paths.get(attachmentsPath);

        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
                log.info("Storage created " + Paths.get(attachmentsPath).toAbsolutePath().toString());
            } catch (IOException e) {
                log.warning("Storage creating process FAIL" + System.lineSeparator() +
                        "Exception: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private MailMessage compileMailMessage(Message message) throws IOException, MessagingException {

        MailMessage mailMessage = new MailMessage();

        JsonNode messageNode;
        ObjectMapper mapper = new ObjectMapper();
        messageNode = mapper.readTree(message.toPrettyString());

        rawMessages.put(messageNode);

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
                HEADERS, "name", "Date");
        jsonNode.ifPresent(jsonNode1 -> mailMessage.setDate(jsonNode1.get(VALUE).asText()));

        // getting subject
        jsonNode = getJsonNodeByField(messagePayloadNode,
                HEADERS, "name", "Subject");
        jsonNode.ifPresent(jsonNode1 -> mailMessage.setSubject(jsonNode1.get(VALUE).asText()));

        // getting attachments
        List<MessagePart> parts = message.getPayload().getParts();
        if (parts != null && !parts.isEmpty()) {
            String mailAttachmentFileNames = "";
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
                    mailAttachmentFileNames = mailAttachmentFileNames + " " + attachmentFilename;

                    // saving attachment file to local storage
                    storeAttachments(client, userId, message.getId(), attachmentId,
                            attachmentFilename, attachmentsPath);
                }
            }
            mailMessage.setAttachments(mailAttachmentFileNames);
        }

        // getting from
        jsonNode = getJsonNodeByField(messagePayloadNode,
                HEADERS, "name", "From");
        jsonNode.ifPresent(jsonNode1 -> mailMessage.setFrom(jsonNode1.get(VALUE).asText()));
        // getting to
        jsonNode = getJsonNodeByField(messagePayloadNode,
                HEADERS, "name", "To");
        jsonNode.ifPresent(jsonNode1 -> mailMessage.setTo(jsonNode1.get(VALUE).asText()));

        log.info("Mail massage #" + ++mailCounter);

        return mailMessage;
    }

    private Optional<JsonNode> getJsonNodeByField(JsonNode requestJsonNode, String groupFieldName,
                                                  String fieldName, String fieldValue) {
        return StreamSupport.stream(requestJsonNode.get(groupFieldName).spliterator(), false)
                .filter(node -> fieldValue.equals(node.get(fieldName).asText()))
                .findFirst();
    }
}
