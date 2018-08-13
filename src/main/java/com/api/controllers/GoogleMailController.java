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
import com.google.api.services.gmail.model.ListThreadsResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.Thread;
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
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.servlet.http.HttpServletRequest;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
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
    private static final String REGEX_VALUE_IN_PARENTHESES = "\\((.*?)\\)";
    private static HttpTransport httpTransport;
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static com.google.api.services.gmail.Gmail client;

    private Set<String> nameOfAttachments = new HashSet<>();
    private long attachmentNumber = 0;

    private String attachmentDirectoryName = "attachments";
    private String attachmentsPath;
    private String csvFileName = "Mail_Messages.csv";

    private GoogleClientSecrets clientSecrets;
    private GoogleAuthorizationCodeFlow flow;
    private Credential credential;

    private long mailCounter;

    private List<String> labelIds = new ArrayList<>();

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

    @Value("${gmail.filter.query}")
    private String query;

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
        JSONArray rawMessages = new JSONArray();

        mailCounter = 0;

        try {

            TokenResponse response = flow.newTokenRequest(code).setRedirectUri(redirectUri).execute();
            log.info("Token Received");

            credential = flow.createAndStoreCredential(response, "userID");
            client = new com.google.api.services.gmail.Gmail.Builder(httpTransport, JSON_FACTORY, credential)
                    .setApplicationName(APPLICATION_NAME).build();

            /*
             * Filter filter = new Filter().setCriteria(new
             * FilterCriteria().setFrom("a2cart.com@gmail.com"))
             * .setAction(new FilterAction()); Filter result =
             * client.users().settings().filters().create("me",
             * filter).execute();
             *
             * System.out.println("Created filter " + result.getId());
             */

//			String query = "subject:'SDK Test message'";

//            ListMessagesResponse MsgResponse;

            String basicLabel = getBasicLabelForMultipleLabels(query);

                query = getQueryForMultipleLabelsSearching(client, userId, basicLabel);

            log.info("Searching query: " + query);

            {


                List<Message> listOfMessages = listMessagesMatchingQuery(client, userId, query);

//                MsgResponse = client.users().messages().list(userId).setQ(query).execute();
                List<Message> messages = new ArrayList<>();
                List<MailMessage> mailMessageList = new ArrayList<>();

//                List<Message> listOfMessages = MsgResponse.getMessages();


                if (listOfMessages != null) {

                    System.out.println("-----------------------------------------------------");
                    System.out.println("Messages found: " + listOfMessages.size());
                    createStorage(directoryPath);


                    for (Message msg : listOfMessages) {
                        messages.add(msg);
                        Message message = client.users().messages().get(userId, msg.getId()).execute();

                        MailMessage mailMessage = compileMailMessage(message);
                        mailMessageList.add(mailMessage);
                        rawMessages.put(mailMessage);

                        /*
                         * if (MsgResponse.getNextPageToken() != null) { String
                         * pageToken = MsgResponse.getNextPageToken(); MsgResponse =
                         * client.users().messages().list(userId).setQ(query).
                         * setPageToken(pageToken).execute(); } else { break; }
                         */
                    }
                    rawMsgReq.put("messages", rawMessages);


                    String attachmentDirectoryPath = directoryPath + "/" + attachmentDirectoryName;
                    System.out.println("Attachments are stored in \n\t"
                            + Paths.get(attachmentDirectoryPath).toAbsolutePath().toString());

                    String filePath = directoryPath + "/" + csvFileName;
                    CSVFileWriter.writeCSVFile(filePath, mailMessageList);

                    System.out.println("Messages are stored in CSV file \n\t"
                            + Paths.get(filePath).toAbsolutePath().toString());
                }
                System.out.println("Total messages processed: " + mailMessageList.size());
                System.out.println("next page");
            }
            System.out.println("-----------------------------------------------------");
        } catch (Exception e) {
            System.out.println("exception cached: " + e.getMessage());
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
                e.printStackTrace();
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
        Matcher m = Pattern.compile(REGEX_VALUE_IN_PARENTHESES).matcher(basicQuery);
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
                            .map(Label::getName)
                            .filter(l -> l.startsWith(basicLabel))
                            .collect(Collectors.joining("|"));
                }
                log.info("Labels:");
                for (Label label : labels) {
                    log.info(String.format("\t%s", label.getName()));
                }
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
                System.out.println("Storage created " + Paths.get(attachmentsPath).toAbsolutePath().toString());
            } catch (IOException e) {
                System.out.println("Storage creating process FAIL \n" + "Exception: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private MailMessage compileMailMessage(Message message) throws IOException, MessagingException {

        MailMessage mailMessage = new MailMessage();
        if (mailCounter > 1700) {
            JsonNode messageNode;
            ObjectMapper mapper = new ObjectMapper();
            messageNode = mapper.readTree(message.toPrettyString());

            // getting message id
            String messageId = messageNode.get("id").asText();
            mailMessage.setId(messageId);

        // getting snippet
        mailMessage.setSnippet(messageNode.get("snippet").asText());

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
                    storeAttachments(client, userId, messageId, attachmentId,
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
    }
//        MimeMessage mimeMessage = getMimeMessage(client, userId, messageId);
        System.out.println("Mail massage #" + ++mailCounter);

        return mailMessage;
    }

    private Optional<JsonNode> getJsonNodeByField(JsonNode requestJsonNode, String groupFieldName,
                                                  String fieldName, String fieldValue) {
        return StreamSupport.stream(requestJsonNode.get(groupFieldName).spliterator(), false)
                .filter(node -> fieldValue.equals(node.get(fieldName).asText()))
                .findFirst();
    }


    /**
     * Get a Message and use it to create a MimeMessage.
     *
     * @param service   Authorized Gmail API instance.
     * @param userId    User's email address. The special value "me"
     *                  can be used to indicate the authenticated user.
     * @param messageId ID of Message to retrieve.
     * @return MimeMessage MimeMessage populated from retrieved Message.
     * @throws IOException
     * @throws MessagingException
     */
    private static MimeMessage getMimeMessage(Gmail service, String userId, String messageId)
            throws IOException, MessagingException {
        Message message = service.users().messages().get(userId, messageId).setFormat("raw").execute();

        Base64 base64Url = new Base64(true);
        byte[] emailBytes = base64Url.decodeBase64(message.getRaw());

        Properties props = new Properties();
        Session session = Session.getDefaultInstance(props, null);

        MimeMessage email = new MimeMessage(session, new ByteArrayInputStream(emailBytes));

        return email;
    }

    /**
     * List all Threads of the user's mailbox with labelIds applied.
     *
     * @param service  Authorized Gmail API instance.
     * @param userId   User's email address. The special value "me"
     *                 can be used to indicate the authenticated user.
     * @param labelIds String used to filter the Threads listed.
     * @throws IOException
     */
    public static void listThreadsWithLabels(Gmail service, String userId,
                                             List<String> labelIds) throws IOException {
        ListThreadsResponse response = service.users().threads().list(userId).setLabelIds(labelIds).execute();
        List<Thread> threads = new ArrayList<Thread>();
        while (response.getThreads() != null) {
            threads.addAll(response.getThreads());
            if (response.getNextPageToken() != null) {
                String pageToken = response.getNextPageToken();
                response = service.users().threads().list(userId).setLabelIds(labelIds)
                        .setPageToken(pageToken).execute();
            } else {
                break;
            }
        }

        for (Thread thread : threads) {
            System.out.println(thread.toPrettyString());

            List<Message> messages = thread.getMessages();
        }
    }

    private void printMessages(List<Message> listOfMessages) {
        try {
            if (listOfMessages != null) {

                List<MailMessage> newMailMessagesList = new ArrayList<>();
                System.out.println("-----------------------------------------------------");
                System.out.println("Messages found: " + listOfMessages.size());
                createStorage(directoryPath);

                int counter = 0;
                for (Message msg : listOfMessages) {
                    Message message = client.users().messages().get(userId, msg.getId()).execute();
                    MailMessage mailMessage = compileMailMessage(message);
                    newMailMessagesList.add(mailMessage);
                    System.out.println("message \t" + ++counter);
                }


                String attachmentDirectoryPath = directoryPath + "/" + attachmentDirectoryName;
                System.out.println("Attachments are stored in \n\t"
                        + Paths.get(attachmentDirectoryPath).toAbsolutePath().toString());

                String filePath = directoryPath + "/" + csvFileName;
                CSVFileWriter.writeCSVFile(filePath, newMailMessagesList);

                System.out.println("Messages are stored in CSV file \n\t"
                        + Paths.get(filePath).toAbsolutePath().toString());
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (MessagingException e) {
            e.printStackTrace();
        }
    }

    /**
     * List all Threads of the user's mailbox matching the query.
     *
     * @param service Authorized Gmail API instance.
     * @param userId  User's email address. The special value "me"
     *                can be used to indicate the authenticated user.
     * @param query   String used to filter the Threads listed.
     * @throws IOException
     */
    public static void listThreadsMatchingQuery(Gmail service, String userId,
                                                String query) throws IOException {
        ListThreadsResponse response = service.users().threads().list(userId).setQ(query).execute();
        List<Thread> threads = new ArrayList<Thread>();
        while (response.getThreads() != null) {
            threads.addAll(response.getThreads());
            if (response.getNextPageToken() != null) {
                String pageToken = response.getNextPageToken();
                response = service.users().threads().list(userId).setQ(query).setPageToken(pageToken).execute();
            } else {
                break;
            }
        }

        List<Thread> threadsWithMsg = threads.stream()
                .filter(t -> (t.getMessages() != null && !t.getMessages().isEmpty()))
                .collect(Collectors.toList());

        for (Thread thread : threadsWithMsg) {
            System.out.println(thread.toPrettyString());
        }
    }



}
