package searchengine.dto.search;

import lombok.Data;

@Data
public class SearchRequest {
    private String query;
    private String siteUrl;
    private int offset;
    private int limit;

    public SearchRequest(String query, String siteUrl, int offset, int limit) {
        this.query = query;
        this.siteUrl = siteUrl;
        this.offset = offset;
        this.limit = limit;
    }
}
