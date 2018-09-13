package com.api.controllers;

import com.api.model.MailMessage;
import com.api.scv.CSVFileWriter;
import com.api.service.MailService;
import com.fasterxml.jackson.databind.JsonNode;
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
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Message;
import lombok.extern.java.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Log
@Controller
@RestController
public class GoogleMailController implements ApplicationListener<ApplicationReadyEvent> {

    @Autowired
    @Qualifier("MailServiceImpl")
    private MailService mailService;

    private static final String APPLICATION_NAME = "SDK mail messages export project";

    private static HttpTransport httpTransport;
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static com.google.api.services.gmail.Gmail client;

    private GoogleAuthorizationCodeFlow flow;

    @Value("${gmail.client.clientId}")
    private String clientId;

    @Value("${gmail.client.clientSecret}")
    private String clientSecret;

    @Value("${launch.url}")
    private String startUri;

    @Value("${gmail.client.redirectUri}")
    private String redirectUri;

    @Value("${store.csv.file.name}")
    private String csvFileName;

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
    public ResponseEntity<String> oauth2Callback(@RequestParam(value = "code") String code) {
        String userId = "me";
        JSONObject rawMsgReq = new JSONObject();

        try {
            TokenResponse response = flow.newTokenRequest(code).setRedirectUri(redirectUri).execute();
            log.info("Token Received");

            Credential credential = flow.createAndStoreCredential(response, "userID");
            client = new com.google.api.services.gmail.Gmail.Builder(httpTransport, JSON_FACTORY, credential)
                    .setApplicationName(APPLICATION_NAME).build();

            String query = mailService.getQueryForMultipleLabelsSearching(client, userId);
            log.info("Searching query: " + query);

            final long startTime = System.currentTimeMillis();
            log.info("-----------------------------------------------------");

            List<Message> listOfMessages = mailService.listMessagesMatchingQuery(client, userId, query);

            List<MailMessage> mailMessageList = new ArrayList<>();

            if (listOfMessages != null) {

                log.info("Messages found: " + listOfMessages.size());

                mailMessageList = listOfMessages.parallelStream()
                        .map(m -> {

                            try {
                                Message message = client.users().messages().get(userId, m.getId()).execute();
                                return mailService.compileMailMessage(client, userId, message);
                            } catch (IOException e) {
                                log.warning(e.getMessage());
                                return null;
                            }

                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                String attachmentDirectoryPath = mailService.getAttachmentDirectoryPath();
                log.info("Mail attachments are stored in "
                        + Paths.get(attachmentDirectoryPath).toAbsolutePath().toString());

                String filePath = mailService.getDirectoryPath() + "/" + csvFileName;
                CSVFileWriter.writeCSVFile(filePath, mailMessageList);

                log.info("Messages are stored in CSV file "
                        + Paths.get(filePath).toAbsolutePath().toString());
            } else {
                log.info("Messages not found ");
            }
            log.info("-----------------------------------------------------");
            final long endTime = System.currentTimeMillis();

            long totalExecutionTime = endTime - startTime;
            log.info(String.format("Total execution time: %s", totalExecutionTime));
            rawMsgReq.put("total_execution_time", totalExecutionTime);

            long totalMessagesProcessed = mailMessageList.size();
            log.info(String.format("Total messages processed: %s", totalMessagesProcessed));
            rawMsgReq.put("total_messages_processed", totalMessagesProcessed);
            log.info("-----------------------------------------------------");
        } catch (Exception e) {
            log.warning("exception cached: " + e.getMessage());
        } /*finally {
            System.exit(0);
        }*/
        return new ResponseEntity<>(rawMsgReq.toString(), HttpStatus.OK);
    }

    /**
     * Method launch browser
     *
     * @param url - start url
     */
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
            GoogleClientSecrets clientSecrets = new GoogleClientSecrets().setWeb(web);
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
}
