package com.api.model;

public class MailMessage {
    private String id = "";

    private String date = "";

    private String from = "";

    private String to = "";

    private String subject = "";

    private String snippet = "";

    private String attachments = "";

    public MailMessage() {
    }

    public MailMessage(String id, String date, String from, String to, String subject, String snippet, String attachments) {
        this.id = id;
        this.date = date;
        this.from = from;
        this.to = to;
        this.subject = subject;
        this.snippet = snippet;
        this.attachments = attachments;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public String getAttachments() {
        return attachments;
    }

    public void setAttachments(String attachments) {
        this.attachments = attachments;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    @Override
    public String toString() {
        return "MailMessage{" +
                "id='" + id + '\'' +
                ", date='" + date + '\'' +
                ", from='" + from + '\'' +
                ", to='" + to + '\'' +
                ", subject='" + subject + '\'' +
                ", snippet='" + snippet + '\'' +
                ", attachments='" + attachments + '\'' +
                '}';
    }
}
