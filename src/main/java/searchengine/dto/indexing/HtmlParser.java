package searchengine.dto.indexing;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import searchengine.config.SearchBot;

import java.io.IOException;

import static org.jsoup.Connection.Response;

public class HtmlParser {

    public static Document getDocument(String path, SearchBot bot) throws IOException {
        return Jsoup.connect(path)
                .userAgent(bot.getName())
                .referrer(bot.getReferrer())
                .get();
    }

    public static String getPagePath(Document document) {
        Response responseConnection = getResponseConnection(document);
        return responseConnection.url().getPath();
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

    public static Elements getHtmlElements(Document document) {
        return document.getElementsByAttribute("href");
    }



}
