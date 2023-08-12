package searchengine.services;

import searchengine.dto.indexing.IndexingResponse;
import searchengine.dto.indexing.SearchRequest;
import searchengine.dto.indexing.SearchResponse;

import java.io.IOException;

public interface IndexingService {
    IndexingResponse startIndexing();
    IndexingResponse stopIndexing();
    IndexingResponse indexPage(String url) throws IOException;
    SearchResponse search(SearchRequest request) throws IOException;
}
