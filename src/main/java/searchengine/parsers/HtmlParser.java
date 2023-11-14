package searchengine.parsers;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import searchengine.config.SearchConfiguration;

import java.io.IOException;

import static org.jsoup.Connection.Response;

public class HtmlParser {

    public static Document getDocumentByUrl(String url, SearchConfiguration bot) throws IOException {
        return Jsoup.connect(url)
                .userAgent(bot.getUserAgentName())
                .referrer(bot.getReferrer())
                .get();
    }

    public static Document getDocumentByHTMLContent(String htmlContent) {
        return Jsoup.parse(htmlContent);
    }

    public static String getTextFromHTMLContent(String htmlContent) {
        Document document = getDocumentByHTMLContent(htmlContent);
        return document.body().text();
    }

    public static String getTitleFromHTMLContent(String htmlContent) {
        Document document = getDocumentByHTMLContent(htmlContent);
        return document.title();
    }

    private static Response getResponseConnection(Document document) {
        return document.connection().response();
    }

    public static int getStatusCode(Document document) {
        Response responseConnection = getResponseConnection(document);
        return responseConnection.statusCode();
    }

    public static String getContent(Document document) {
        return document.html();
    }

    public static Elements getHrefElements(Document document) {
        return document.getElementsByAttribute("href");
    }

}
