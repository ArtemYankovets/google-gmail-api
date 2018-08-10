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
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

@Log
@Controller
@RestController
public class GoogleMailController implements ApplicationListener<ApplicationReadyEvent> {

    private static final String APPLICATION_NAME = "SDK mail messages export project";
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
        System.out.println("Application started ... launching browser now");
        launchBrowser(startUri);
    }

    @RequestMapping(value = "/login/gmail", method = RequestMethod.GET)
    public RedirectView googleConnectionStatus(HttpServletRequest request) throws Exception {
        return new RedirectView(authorize());
    }

    @RequestMapping(value = "/login/gmailCallback", method = RequestMethod.GET, params = "code")
    public ResponseEntity<String> oauth2Callback(@RequestParam(value = "code") String code) throws IOException {

        JSONObject json = new JSONObject();
        JSONArray arr = new JSONArray();

        JSONObject rawMsgReq = new JSONObject();
        JSONArray rawMessages = new JSONArray();

        // String message;
        try {
            TokenResponse response = flow.newTokenRequest(code).setRedirectUri(redirectUri).execute();
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

            ListMessagesResponse MsgResponse = client.users().messages().list(userId).setQ(query).execute();

            List<Message> messages = new ArrayList<>();
            List<MailMessage> mailMessageList = new ArrayList<>();

            if (MsgResponse.getMessages() != null) {

                System.out.println("-----------------------------------------------------");
                System.out.println("Messages found: " + MsgResponse.getMessages().size());
                createStorage(directoryPath);

                for (Message msg : MsgResponse.getMessages()) {
                    messages.add(msg);
                    Message message = client.users().messages().get(userId, msg.getId()).execute();

                    arr.put(message.getSnippet());

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
            System.out.println("-----------------------------------------------------");
        } catch (Exception e) {
            System.out.println("exception cached: " + e.getMessage());
        } finally {
            System.exit(0);
        }

        return new ResponseEntity<>(rawMsgReq.toString(), HttpStatus.OK);
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

        System.out.println("Gmail authorizationUrl ->" + authorizationUrl);
        return authorizationUrl.build();
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
            }
        }
    }

    private MailMessage compileMailMessage(Message message) throws IOException, MessagingException {
        JsonNode messageNode;
        ObjectMapper mapper = new ObjectMapper();
        messageNode = mapper.readTree(message.toPrettyString());

        // getting message id
        MailMessage mailMessage = new MailMessage();
        String messageId = messageNode.get("id").asText();
        mailMessage.setId(messageId);

        // getting snippet
        mailMessage.setSnippet(messageNode.get("snippet").asText());

        // getting date
        JsonNode messagePayloadNode = messageNode.get("payload");
        Optional<JsonNode> jsonNode = getJsonNodeByField(messagePayloadNode,
                "headers", "name", "Date");
        mailMessage.setDate(jsonNode.get().get("value").asText());

        // getting subject
        jsonNode = getJsonNodeByField(messagePayloadNode,
                "headers", "name", "Subject");
        mailMessage.setSubject(jsonNode.get().get("value").asText());

        // getting attachments
        List<MessagePart> parts = message.getPayload().getParts();
        String mailAttachmentFileNames = "";

        for (MessagePart part : parts) {
            String attachmentId = part.getBody().getAttachmentId();

            if (attachmentId != null) {
                String attachmentFilename = part.getFilename();

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

        // getting from
        jsonNode = getJsonNodeByField(messagePayloadNode,
                "headers", "name", "From");
        mailMessage.setFrom(jsonNode.get().get("value").asText());

        // getting to
        jsonNode = getJsonNodeByField(messagePayloadNode,
                "headers", "name", "To");
        mailMessage.setTo(jsonNode.get().get("value").asText());

//        MimeMessage mimeMessage = getMimeMessage(client, userId, messageId);

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
}
