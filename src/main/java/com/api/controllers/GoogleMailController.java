package com.api.controllers;

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

import javax.servlet.http.HttpServletRequest;
import java.awt.*;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log
@Controller
@RestController
public class GoogleMailController implements ApplicationListener<ApplicationReadyEvent> {

	private static final String APPLICATION_NAME = "SDK mail messages export project";
	private static HttpTransport httpTransport;
	private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
	private static com.google.api.services.gmail.Gmail client;

	GoogleClientSecrets clientSecrets;
	GoogleAuthorizationCodeFlow flow;
	Credential credential;

	@Value("${gmail.client.clientId}")
	private String clientId;

	@Value("${gmail.client.clientSecret}")
	private String clientSecret;

	@Value("${launch.url}")
	private String startUri;

	@Value("${gmail.client.redirectUri}")
	private String redirectUri;

	@Override
	public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {
		System.out.println("Application started ... launching browser now");
		launchBrowse(startUri);
	}

	@RequestMapping(value = "/login/gmail", method = RequestMethod.GET)
	public RedirectView googleConnectionStatus(HttpServletRequest request) throws Exception {
		return new RedirectView(authorize());
	}

	@RequestMapping(value = "/login/gmailCallback", method = RequestMethod.GET, params = "code")
	public ResponseEntity<String> oauth2Callback(@RequestParam(value = "code") String code) {

		// System.out.println("code->" + code + " userId->" + userId + "
		// query->" + query);

		JSONObject json = new JSONObject();
		JSONArray arr = new JSONArray();

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



			String userId = "me";
//			String query = "subject:'SDK Test message'";
			String query = "label:(FRACTAL|API)";

			ListMessagesResponse MsgResponse = client.users().messages().list(userId).setQ(query).execute();

			List<Message> messages = new ArrayList<>();

			System.out.println("message length:" + MsgResponse.getMessages().size());

			Set<String> nameOfAttachments = new HashSet<>();
			int attachmentNumber = 0;

			for (Message msg : MsgResponse.getMessages()) {

				messages.add(msg);

				Message message = client.users().messages().get(userId, msg.getId()).execute();
				System.out.println("--------------------------------------");
				System.out.println("msgId :" + message.getId());

				List<MessagePart> parts = message.getPayload().getParts();


				for(MessagePart part: parts) {
					String attachmentId = part.getBody().getAttachmentId();

					if (attachmentId != null) {
						System.out.println("\tattachments : ");
						System.out.println("\t|\tid : " + attachmentId);

						String attachmentFilename = part.getFilename();

						String regex = "(.+?)(\\.[^.]*$|$)";
						String pureFileName = "";
						String fileNameExtention = "";
						Matcher m = Pattern.compile(regex).matcher(attachmentFilename);
						if(m.matches())
						{
							pureFileName = m.group(1);
							fileNameExtention = attachmentFilename.substring(pureFileName.length(), attachmentFilename.length());
						}

						if (nameOfAttachments.contains(pureFileName)) {
							//todo attachment number should inc for the in same filename ranges
							attachmentNumber++;
							attachmentFilename = pureFileName + "_" +attachmentNumber + fileNameExtention;

						}
						nameOfAttachments.add(pureFileName);
						System.out.println("\t|\tfilename : " + attachmentFilename);

						// saving attachment file
						storeAttachments(client, userId, message.getId(), attachmentId, attachmentFilename);

					}

				}
				System.out.println("snippet :" + message.getSnippet());
//				System.out.println("pretty :" + message.toPrettyString());
				System.out.println("--------------------------------------");

				arr.put(message.getSnippet());

				/*
				 * if (MsgResponse.getNextPageToken() != null) { String
				 * pageToken = MsgResponse.getNextPageToken(); MsgResponse =
				 * client.users().messages().list(userId).setQ(query).
				 * setPageToken(pageToken).execute(); } else { break; }
				 */
			}
			json.put("response", arr);

			for (Message msg : messages) {

				System.out.println("msg: " + msg.toPrettyString());
			}

		} catch (Exception e) {

			System.out.println("exception cached ");
			e.printStackTrace();
		}

		return new ResponseEntity<>(json.toString(), HttpStatus.OK);
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

		System.out.println("gamil authorizationUrl ->" + authorizationUrl);
		return authorizationUrl.build();
	}

	private void launchBrowse(String url) {
		if(Desktop.isDesktopSupported()){
			Desktop desktop = Desktop.getDesktop();
			try {
				desktop.browse(new URI(url));
			} catch (IOException | URISyntaxException e) {
				e.printStackTrace();
			}
		}else{
			Runtime runtime = Runtime.getRuntime();
			try {
				runtime.exec("rundll32 url.dll,FileProtocolHandler " + url);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Save the attachments in a given email.
	 *
	 * @param service Authorized Gmail API instance.
	 * @param userId User's email address. The special value "me"
	 * can be used to indicate the authenticated user.
	 * @param messageId ID of Message containing attachment..
	 * @throws IOException
	 */
	private void storeAttachments(Gmail service, String userId, String messageId, String attachmentId, String filename)
			throws IOException {

				MessagePartBody attachPart = service.users().messages().attachments().
						get(userId, messageId, attachmentId).execute();

				Base64 base64Url = new Base64(true);
				byte[] fileByteArray = base64Url.decodeBase64(attachPart.getData());
				FileOutputStream fileOutFile =
						new FileOutputStream("D:\\attachments\\" + filename);
				fileOutFile.write(fileByteArray);
				fileOutFile.close();
	}
}
