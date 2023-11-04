package searchengine.services;

import searchengine.dto.search.SearchRequest;
import searchengine.dto.search.SearchResponse;
import java.io.IOException;

public interface SearchService {
    SearchResponse search(SearchRequest request) throws IOException;
}
